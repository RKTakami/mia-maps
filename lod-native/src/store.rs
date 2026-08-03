//! Persistence: sections on disk, and the LOD pyramid kept current as they arrive.
//!
//! Backed by `redb` — embedded, ACID, pure Rust. Pure Rust is not incidental: it is what keeps the
//! crate free of a per-platform native matrix.

use crate::lod::{build_parent, Octant};
use crate::section::{BlockId, Drawable, Section, AIR, BIOME_CELLS, CELLS, EDGE};
use std::collections::HashMap;
use std::sync::{Mutex, RwLock};
use redb::{Database, ReadableTable, ReadableTableMetadata, TableDefinition};
use std::path::Path;

const SECTIONS: TableDefinition<u128, &[u8]> = TableDefinition::new("sections");

/// Parents known to be out of date. Persisted rather than held in memory, so a crash between
/// indexing and a flush leaves work *recorded* rather than lost — otherwise stale parents would
/// survive indefinitely with nothing aware they were wrong.
const DIRTY: TableDefinition<u128, ()> = TableDefinition::new("dirty");

/// `BlockId -> (flags, canonical block-state string)`.
///
/// **This is what makes stored ids stable.** A game's runtime block ids shift with the version and
/// the mod set, so storing them directly would silently reinterpret terrain after any change. Ids
/// here are assigned by this store and anchored to the canonical string, which does not move.
///
/// Flags live here rather than per cell: one entry serves the 4096 cells that reference it, and
/// folding needs them to know what is visible.
const BLOCKS: TableDefinition<u32, (u16, &str)> = TableDefinition::new("blocks");

/// `canonical string -> BlockId`. The reverse direction, so interning does not scan.
const BLOCK_IDS: TableDefinition<&str, u32> = TableDefinition::new("block_ids");

/// Deepest level the pyramid is maintained to. Level N cells are `2^N` blocks, so level 6 is
/// 64-block cells — the coarse end the whole-Abyss view and far rendering want.
pub const MAX_LEVEL: u8 = 6;

#[derive(Debug)]
pub enum StoreError {
    Db(String),
    Corrupt(&'static str),
}

impl std::fmt::Display for StoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StoreError::Db(e) => write!(f, "store: {e}"),
            StoreError::Corrupt(e) => write!(f, "store: corrupt value: {e}"),
        }
    }
}
impl std::error::Error for StoreError {}

macro_rules! db_err {
    ($e:expr) => {
        $e.map_err(|e| StoreError::Db(e.to_string()))?
    };
}

/// Pack a section address into one ordered key.
///
/// Coordinates are biased by `i32::MIN` before packing so that **signed order is preserved** in the
/// key's unsigned ordering. A raw two's-complement cast would sort every negative coordinate after
/// every positive one, which is invisible for exact lookups and quietly wrong the first time anyone
/// range-scans a region.
///
/// Level occupies the high bits, so a scan of one level is contiguous.
#[inline]
pub fn key(level: u8, x: i32, y: i32, z: i32) -> u128 {
    let bias = |v: i32| (v as i64 - i32::MIN as i64) as u128; // 0 ..= u32::MAX, order-preserving
    ((level as u128) << 96) | (bias(x) << 64) | (bias(y) << 32) | bias(z)
}

/// Cheap content hash of a section, for skipping unchanged rewrites.
///
/// Covers the palette and the indices — everything `encode` would emit — so two sections hashing
/// equal really do store the same terrain.
fn content_hash(section: &Section) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    section.palette().hash(&mut h);
    section.indices().hash(&mut h);
    section.biomes().hash(&mut h);
    h.finish()
}

/// Inverse of [`key`].
#[inline]
pub fn unkey(k: u128) -> (u8, i32, i32, i32) {
    let unbias = |v: u128| ((v as u32) as i64 + i32::MIN as i64) as i32;
    (
        (k >> 96) as u8,
        unbias(k >> 64),
        unbias(k >> 32),
        unbias(k),
    )
}

// ---- value encoding ----------------------------------------------------------------------------
//
// byte 0 = format tag. Values are SELF-DESCRIBING, so a newer writer can coexist with values an
// older one wrote — no migration pass, and a store converges to the new form as sections are
// re-indexed.
//
//   0 -> all air. Nothing follows. Most of the world is air and storing 8 KB of zeroes for it would
//        dominate the database.
//   1 -> palette + lz4 over u16-per-cell indices. Read-only now; see TAG_PACKED.
//   2 -> palette + lz4 over indices bit-packed to the palette's width.
//
// v1 spent 16 bits per cell regardless of palette size. Measured against a real store, a median
// section has 3 distinct blocks — 2 bits — so 14 of every 16 bits were zero padding that lz4 then
// had to work to remove. Packing first takes 8192 raw bytes to ~1077, and pushes most values under
// the size where the B-tree packs them well.

const TAG_AIR: u8 = 0;
const TAG_PALETTED_U16: u8 = 1;
const TAG_PACKED: u8 = 2;
/// As TAG_PACKED, plus 64 biome ids. A new tag rather than a field on the old one, so the 149k
/// sections already indexed keep reading — they simply come back untinted and correct themselves
/// when that terrain is next indexed.
const TAG_PACKED_BIOMES: u8 = 3;

/// Bits needed to index a palette of `len` entries. Zero when there is nothing to choose between —
/// a uniform section stores no index data at all, only its single palette entry.
#[inline]
pub fn index_bits(len: usize) -> usize {
    if len <= 1 {
        0
    } else {
        (u32::BITS - (len as u32 - 1).leading_zeros()) as usize
    }
}

/// Pack indices LSB-first at `bits` each.
fn pack(indices: &[u16], bits: usize) -> Vec<u8> {
    if bits == 0 {
        return Vec::new();
    }
    let mut out = vec![0u8; (indices.len() * bits).div_ceil(8)];
    let mut pos = 0usize;
    for &v in indices {
        let v = v as u32;
        for b in 0..bits {
            if (v >> b) & 1 == 1 {
                out[(pos + b) / 8] |= 1 << ((pos + b) % 8);
            }
        }
        pos += bits;
    }
    out
}

/// Inverse of [`pack`].
fn unpack(bytes: &[u8], bits: usize, count: usize) -> Option<Vec<u16>> {
    if bits == 0 {
        return Some(vec![0u16; count]);
    }
    if bytes.len() < (count * bits).div_ceil(8) {
        return None;
    }
    let mut out = Vec::with_capacity(count);
    let mut pos = 0usize;
    for _ in 0..count {
        let mut v = 0u32;
        for b in 0..bits {
            let byte = bytes[(pos + b) / 8];
            if (byte >> ((pos + b) % 8)) & 1 == 1 {
                v |= 1 << b;
            }
        }
        out.push(v as u16);
        pos += bits;
    }
    Some(out)
}

fn encode(section: &Section) -> Vec<u8> {
    if section.is_air() {
        return vec![TAG_AIR];
    }
    let n = section.palette_len();
    let bits = index_bits(n);
    let has_biomes = section.biomes().len() == BIOME_CELLS;
    let mut out = Vec::with_capacity(1 + 2 + n * 4 + 1 + CELLS / 4);
    out.push(if has_biomes { TAG_PACKED_BIOMES } else { TAG_PACKED });
    out.extend_from_slice(&(n as u16).to_le_bytes());
    for id in section.palette() {
        out.extend_from_slice(&id.to_le_bytes());
    }
    out.push(bits as u8);
    if bits > 0 {
        let frame = lz4_flex::compress_prepend_size(&pack(section.indices(), bits));
        if has_biomes {
            // Explicit length: with a second frame following, the reader must know where this one
            // ends. Recompressing to find out would work until lz4 output stopped being byte-stable.
            out.extend_from_slice(&(frame.len() as u32).to_le_bytes());
        }
        out.extend_from_slice(&frame);
    }
    if has_biomes {
        // Raw rather than packed: 64 values is already negligible beside 4096 cells, and a section
        // usually holds one or two biomes so lz4 flattens it anyway.
        let mut b = Vec::with_capacity(BIOME_CELLS * 4);
        for id in section.biomes() {
            b.extend_from_slice(&id.to_le_bytes());
        }
        out.extend_from_slice(&lz4_flex::compress_prepend_size(&b));
    }
    out
}

fn decode(bytes: &[u8]) -> Result<Section, StoreError> {
    match bytes.first() {
        None => Err(StoreError::Corrupt("empty value")),
        Some(&TAG_AIR) => Ok(Section::air()),
        Some(t) if *t == TAG_PACKED || *t == TAG_PACKED_BIOMES => {
            let with_biomes = *t == TAG_PACKED_BIOMES;
            if bytes.len() < 3 {
                return Err(StoreError::Corrupt("truncated header"));
            }
            let n = u16::from_le_bytes([bytes[1], bytes[2]]) as usize;
            let pal_end = 3 + n * 4;
            if bytes.len() < pal_end + 1 {
                return Err(StoreError::Corrupt("truncated palette"));
            }
            let mut palette = Vec::with_capacity(n);
            for i in 0..n {
                let o = 3 + i * 4;
                palette.push(u32::from_le_bytes([bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3]]));
            }
            let bits = bytes[pal_end] as usize;
            if bits != index_bits(n) {
                return Err(StoreError::Corrupt("index width disagrees with palette"));
            }
            let mut cursor = pal_end + 1;
            let indices = if bits == 0 {
                vec![0u16; CELLS]
            } else {
                let frame = if with_biomes {
                    // Length-prefixed, because a biome frame follows it.
                    if bytes.len() < cursor + 4 {
                        return Err(StoreError::Corrupt("truncated index frame length"));
                    }
                    let n = u32::from_le_bytes([
                        bytes[cursor], bytes[cursor + 1], bytes[cursor + 2], bytes[cursor + 3],
                    ]) as usize;
                    cursor += 4;
                    if bytes.len() < cursor + n {
                        return Err(StoreError::Corrupt("truncated index frame"));
                    }
                    let f = &bytes[cursor..cursor + n];
                    cursor += n;
                    f
                } else {
                    &bytes[cursor..]
                };
                let raw = lz4_flex::decompress_size_prepended(frame)
                    .map_err(|_| StoreError::Corrupt("lz4"))?;
                unpack(&raw, bits, CELLS).ok_or(StoreError::Corrupt("packed indices truncated"))?
            };
            let mut section = Section::from_parts(palette, indices)
                .ok_or(StoreError::Corrupt("index out of palette"))?;
            if with_biomes {
                let raw = lz4_flex::decompress_size_prepended(&bytes[cursor..])
                    .map_err(|_| StoreError::Corrupt("lz4 biomes"))?;
                if raw.len() != BIOME_CELLS * 4 {
                    return Err(StoreError::Corrupt("biome array wrong length"));
                }
                let biomes: Vec<BlockId> = raw
                    .chunks_exact(4)
                    .map(|c| u32::from_le_bytes([c[0], c[1], c[2], c[3]]))
                    .collect();
                section.set_biomes(&biomes);
            }
            Ok(section)
        }
        Some(&TAG_PALETTED_U16) => {
            if bytes.len() < 3 {
                return Err(StoreError::Corrupt("truncated header"));
            }
            let n = u16::from_le_bytes([bytes[1], bytes[2]]) as usize;
            let pal_end = 3 + n * 4;
            if bytes.len() < pal_end {
                return Err(StoreError::Corrupt("truncated palette"));
            }
            let mut palette = Vec::with_capacity(n);
            for i in 0..n {
                let o = 3 + i * 4;
                palette.push(u32::from_le_bytes([bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3]]));
            }
            let raw = lz4_flex::decompress_size_prepended(&bytes[pal_end..])
                .map_err(|_| StoreError::Corrupt("lz4"))?;
            if raw.len() != CELLS * 2 {
                return Err(StoreError::Corrupt("index array wrong length"));
            }
            let mut indices = Vec::with_capacity(CELLS);
            for c in raw.chunks_exact(2) {
                indices.push(u16::from_le_bytes([c[0], c[1]]));
            }
            Section::from_parts(palette, indices).ok_or(StoreError::Corrupt("index out of palette"))
        }
        Some(_) => Err(StoreError::Corrupt("unknown format tag")),
    }
}

// ---- store -------------------------------------------------------------------------------------

/// Sections buffered before a commit (override with MIA_LODS_BATCH for tuning experiments). redb is copy-on-write, so every commit rewrites the B-tree
/// path it touches; committing per section turned 28 MB of data into a 97 MB file and cost a
/// transaction per chunk section. Batching amortises both.
fn commit_batch() -> usize {
    std::env::var("MIA_LODS_BATCH").ok().and_then(|v| v.parse().ok()).unwrap_or(256)
}

pub struct Store {
    db: Database,
    path: std::path::PathBuf,
    /// Sections written but not yet committed. Bounded by COMMIT_BATCH.
    pending: Mutex<Vec<(i32, i32, i32, Section)>>,
    /// Content hash of the last version written for each section, this session.
    ///
    /// Chunks reload constantly as a player moves, and a real session measured **48% of all writes**
    /// as re-indexing terrain that had not changed. Each one was a full re-encode plus a B-tree
    /// overwrite, and overwrites are what generate the copy-on-write churn that inflates the file.
    /// Skipping them attacks the cause rather than the symptom.
    seen: Mutex<HashMap<u128, u64>>,
    skipped: std::sync::atomic::AtomicU64,
    /// Block flags, mirrored in memory. Folding consults this for every cell of every rebuild, and
    /// a database read per cell would dominate. Rebuilt on open and updated as ids are interned.
    flags: RwLock<HashMap<BlockId, u16>>,
}

/// Adapter so folding can ask "is this drawable" without knowing about the store.
struct FlagLookup<'a>(&'a HashMap<BlockId, u16>);

impl Drawable for FlagLookup<'_> {
    fn is_drawable(&self, id: BlockId) -> bool {
        self.0.get(&id).is_some_and(|f| f & crate::section::flags::OPAQUE != 0)
    }
}

impl Store {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, StoreError> {
        let path = path.as_ref().to_path_buf();
        let db = db_err!(Database::create(&path));
        // Create the table up front so a read on a fresh store sees an empty table rather than an
        // error about a missing one.
        let w = db_err!(db.begin_write());
        {
            db_err!(w.open_table(SECTIONS));
            db_err!(w.open_table(DIRTY));
            db_err!(w.open_table(BLOCKS));
            db_err!(w.open_table(BLOCK_IDS));
        }
        db_err!(w.commit());

        // Mirror the block flags in memory up front; folding touches them per cell.
        let mut flags = HashMap::new();
        {
            let r = db_err!(db.begin_read());
            let t = db_err!(r.open_table(BLOCKS));
            for e in db_err!(t.iter()) {
                let (k, v) = db_err!(e);
                flags.insert(k.value(), v.value().0);
            }
        }
        Ok(Store {
            db,
            path,
            pending: Mutex::new(Vec::new()),
            seen: Mutex::new(HashMap::new()),
            skipped: std::sync::atomic::AtomicU64::new(0),
            flags: RwLock::new(flags),
        })
    }

    /// Get or assign the stable id for a block state, identified by its canonical string.
    ///
    /// `flags` describe the block type once — see [`crate::section::flags`]. Re-interning an
    /// existing key updates its flags, so a consumer that refines its classification does not have
    /// to re-index the world.
    pub fn intern_block(&self, key: &str, flags: u16) -> Result<BlockId, StoreError> {
        if key.is_empty() {
            return Err(StoreError::Corrupt("empty block key"));
        }
        // Fast path: already known and unchanged.
        {
            let r = db_err!(self.db.begin_read());
            let ids = db_err!(r.open_table(BLOCK_IDS));
            if let Some(existing) = db_err!(ids.get(key)) {
                let id = existing.value();
                if self.flags.read().unwrap().get(&id) == Some(&flags) {
                    return Ok(id);
                }
            }
        }
        let w = db_err!(self.db.begin_write());
        let id;
        {
            let mut ids = db_err!(w.open_table(BLOCK_IDS));
            let mut blocks = db_err!(w.open_table(BLOCKS));
            let existing = db_err!(ids.get(key)).map(|v| v.value());
            id = match existing {
                Some(v) => v,
                None => {
                    // Ids start at 1; 0 is reserved for air and never interned.
                    let next = db_err!(blocks.len()) as u32 + 1;
                    db_err!(ids.insert(key, next));
                    next
                }
            };
            db_err!(blocks.insert(id, (flags, key)));
        }
        db_err!(w.commit());
        self.flags.write().unwrap().insert(id, flags);
        Ok(id)
    }

    /// The canonical string and flags for an id, or `None` if it was never interned.
    pub fn block(&self, id: BlockId) -> Result<Option<(u16, String)>, StoreError> {
        let r = db_err!(self.db.begin_read());
        let t = db_err!(r.open_table(BLOCKS));
        Ok(db_err!(t.get(id)).map(|v| {
            let (f, k) = v.value();
            (f, k.to_string())
        }))
    }

    /// Every interned block, for a consumer building its own id mapping on load.
    pub fn blocks(&self) -> Result<Vec<(BlockId, u16, String)>, StoreError> {
        let r = db_err!(self.db.begin_read());
        let t = db_err!(r.open_table(BLOCKS));
        let mut out = Vec::new();
        for e in db_err!(t.iter()) {
            let (k, v) = db_err!(e);
            let (f, s) = v.value();
            out.push((k.value(), f, s.to_string()));
        }
        Ok(out)
    }

    /// Read one section. `None` means "never seen", which is distinct from "seen and empty" — the
    /// latter is stored as an air section and comes back as `Some`.
    pub fn get(&self, level: u8, x: i32, y: i32, z: i32) -> Result<Option<Section>, StoreError> {
        let r = db_err!(self.db.begin_read());
        let t = db_err!(r.open_table(SECTIONS));
        match db_err!(t.get(key(level, x, y, z))) {
            Some(v) => Ok(Some(decode(v.value())?)),
            None => Ok(None),
        }
    }

    /// Write one section at `level` without touching the pyramid. Prefer [`Store::index`] for
    /// level-0 writes so coarser levels stay current.
    pub fn put(&self, level: u8, x: i32, y: i32, z: i32, section: &Section) -> Result<(), StoreError> {
        let w = db_err!(self.db.begin_write());
        {
            let mut t = db_err!(w.open_table(SECTIONS));
            db_err!(t.insert(key(level, x, y, z), encode(section).as_slice()));
        }
        db_err!(w.commit());
        Ok(())
    }

    /// Record a level-0 section and mark its ancestors as needing rebuild.
    ///
    /// **Cheap on purpose.** Rebuilding the pyramid here would read all eight children at every
    /// level on every write — 48 reads, 7 writes and a commit per section — and chunks arrive tens
    /// per second while a player explores. Marking instead means one write plus a few tiny markers,
    /// and [`Store::flush`] does the folding once for parents that many sections share.
    ///
    /// The markers are **persisted in the same transaction as the section**, so a crash before a
    /// flush leaves the work recorded rather than lost. The pyramid is then always either current or
    /// *known* to be stale — never silently wrong, which is the failure nothing downstream can
    /// detect.
    pub fn index(&self, x: i32, y: i32, z: i32, section: &Section) -> Result<(), StoreError> {
        let k = key(0, x, y, z);
        let hash = content_hash(section);
        {
            let mut seen = self.seen.lock().unwrap();
            if seen.get(&k) == Some(&hash) {
                self.skipped.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                return Ok(());
            }
            // Bounded crudely rather than with an LRU: exceeding this means a session has ranged
            // over an enormous area, and the cost of forgetting is re-writing some sections once.
            if seen.len() >= 400_000 {
                seen.clear();
            }
            seen.insert(k, hash);
        }
        let full = {
            let mut p = self.pending.lock().unwrap();
            p.push((x, y, z, section.clone()));
            p.len() >= commit_batch()
        };
        if full {
            self.commit_pending()?;
        }
        Ok(())
    }

    /// Write buffered sections and their dirty markers in ONE transaction.
    ///
    /// Called automatically once a batch fills, and by [`Store::flush`]. Sections and markers commit
    /// together, so a crash cannot leave terrain stored with nothing recording that its parents are
    /// now stale.
    pub fn commit_pending(&self) -> Result<usize, StoreError> {
        let batch: Vec<(i32, i32, i32, Section)> = {
            let mut p = self.pending.lock().unwrap();
            if p.is_empty() {
                return Ok(0);
            }
            std::mem::take(&mut *p)
        };
        let w = db_err!(self.db.begin_write());
        {
            let mut t = db_err!(w.open_table(SECTIONS));
            let mut d = db_err!(w.open_table(DIRTY));
            for (x, y, z, section) in &batch {
                db_err!(t.insert(key(0, *x, *y, *z), encode(section).as_slice()));
                let (mut cx, mut cy, mut cz) = (*x, *y, *z);
                for level in 0..MAX_LEVEL {
                    cx >>= 1;
                    cy >>= 1;
                    cz >>= 1;
                    db_err!(d.insert(key(level + 1, cx, cy, cz), ()));
                }
            }
        }
        db_err!(w.commit());
        Ok(batch.len())
    }

    /// Rebuild every parent marked dirty. Call periodically while indexing, and once when done.
    ///
    /// Returns how many parents were rebuilt. Levels are processed **coarsest last**, because a
    /// level-2 parent must fold children that level 1 has already refreshed — doing it in the other
    /// order would fold stale input and produce a pyramid that is consistent-looking and wrong.
    ///
    /// One transaction for the whole flush: parents and their markers clear together, so an
    /// interrupted flush redoes work rather than losing it.
    pub fn flush(&self) -> Result<usize, StoreError> {
        // Buffered sections must land before their parents are folded, or the fold reads children
        // that are still only in memory and produces a parent describing terrain the store does not
        // yet contain.
        self.commit_pending()?;
        let pending: Vec<u128> = {
            let r = db_err!(self.db.begin_read());
            let d = db_err!(r.open_table(DIRTY));
            let mut v = Vec::new();
            for e in db_err!(d.iter()) {
                v.push(db_err!(e).0.value());
            }
            v
        };
        if pending.is_empty() {
            return Ok(0);
        }
        // Level lives in the high bits, so sorting the packed key orders by level for free.
        let mut pending = pending;
        pending.sort_unstable();

        // One snapshot for the whole flush: folding asks per cell, and taking the lock each time
        // would dominate.
        let flags_snapshot = self.flags.read().unwrap().clone();

        let w = db_err!(self.db.begin_write());
        let mut rebuilt = 0usize;
        {
            let mut t = db_err!(w.open_table(SECTIONS));
            let mut d = db_err!(w.open_table(DIRTY));
            for k in pending {
                let (level, px, py, pz) = unkey(k);
                let child_level = level - 1;
                // Gather whichever of the eight children exist; missing ones leave their octant air,
                // so a partly-explored parent is still usable rather than absent.
                let mut kids: Vec<(Octant, Section)> = Vec::with_capacity(8);
                for dz in 0..2i32 {
                    for dy in 0..2i32 {
                        for dx in 0..2i32 {
                            let (kx, ky, kz) = (px * 2 + dx, py * 2 + dy, pz * 2 + dz);
                            if let Some(v) = db_err!(t.get(key(child_level, kx, ky, kz))) {
                                kids.push((
                                    Octant::new(dx as usize, dy as usize, dz as usize),
                                    decode(v.value())?,
                                ));
                            }
                        }
                    }
                }
                let refs: Vec<(Octant, &Section)> = kids.iter().map(|(o, s)| (*o, s)).collect();
                let parent = build_parent(&refs, &FlagLookup(&flags_snapshot));
                db_err!(t.insert(key(level, px, py, pz), encode(&parent).as_slice()));
                db_err!(d.remove(k));
                rebuilt += 1;
            }
        }
        db_err!(w.commit());
        Ok(rebuilt)
    }

    /// Walk every stored section and report (count, total encoded bytes, count per level).
    ///
    /// Diagnostics: the gap between this total and the file on disk is what the database costs on
    /// top of the data, which is not something to assume.
    pub fn stats(&self) -> Result<(u64, u64, Vec<u64>), StoreError> {
        let r = db_err!(self.db.begin_read());
        let t = db_err!(r.open_table(SECTIONS));
        let mut count = 0u64;
        let mut bytes = 0u64;
        let mut per_level = vec![0u64; (MAX_LEVEL + 1) as usize];
        for e in db_err!(t.iter()) {
            let (k, v) = db_err!(e);
            let (level, _, _, _) = unkey(k.value());
            if (level as usize) < per_level.len() {
                per_level[level as usize] += 1;
            }
            bytes += v.value().len() as u64;
            count += 1;
        }
        Ok((count, bytes, per_level))
    }

    /// Visit every stored key. For diagnostics that need the coordinates rather than the values —
    /// counting what lies in each Abyss sector, say — without loading a single section body.
    pub fn for_each_key(&self, mut f: impl FnMut(u128)) -> Result<(), StoreError> {
        let r = db_err!(self.db.begin_read());
        let t = db_err!(r.open_table(SECTIONS));
        for e in db_err!(t.iter()) {
            let (k, _) = db_err!(e);
            f(k.value());
        }
        Ok(())
    }

    /// How many stored sections carry biome data. Diagnostics — a coordinate scan cannot answer
    /// this reliably, since it finds whatever happens to be nearest the origin.
    pub fn biome_coverage(&self) -> Result<(u64, u64), StoreError> {
        let r = db_err!(self.db.begin_read());
        let t = db_err!(r.open_table(SECTIONS));
        let (mut with, mut without) = (0u64, 0u64);
        for e in db_err!(t.iter()) {
            let (_, v) = db_err!(e);
            match decode(v.value()) {
                Ok(sec) if !sec.biomes().is_empty() => with += 1,
                Ok(_) => without += 1,
                Err(_) => without += 1,
            }
        }
        Ok((with, without))
    }

    /// Encoded size of every stored value, as (size, count) pairs. Diagnostics.
    pub fn value_sizes(&self) -> Result<Vec<(usize, u64)>, StoreError> {
        let r = db_err!(self.db.begin_read());
        let t = db_err!(r.open_table(SECTIONS));
        let mut m: HashMap<usize, u64> = HashMap::new();
        for e in db_err!(t.iter()) {
            let (_, v) = db_err!(e);
            *m.entry(v.value().len()).or_insert(0) += 1;
        }
        Ok(m.into_iter().collect())
    }

    /// Reclaim free space, returning (bytes before, bytes after).
    ///
    /// A store that only ever grows is a problem on a machine someone also plays games on. redb
    /// leaves freed pages in the file — overwriting a section, which happens every time a chunk is
    /// revisited, frees the old copy — so this is worth running when a session ends.
    pub fn compact(&mut self) -> Result<(u64, u64), StoreError> {
        let before = self.file_len();
        db_err!(self.db.compact());
        Ok((before, self.file_len()))
    }

    fn file_len(&self) -> u64 {
        std::fs::metadata(&self.path).map(|m| m.len()).unwrap_or(0)
    }

    /// Sections skipped as unchanged since this store was opened. Diagnostics.
    pub fn skipped(&self) -> u64 {
        self.skipped.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// Parents currently awaiting a rebuild. Diagnostics and tests.
    pub fn dirty_count(&self) -> Result<u64, StoreError> {
        let r = db_err!(self.db.begin_read());
        let d = db_err!(r.open_table(DIRTY));
        Ok(db_err!(d.len()))
    }

    /// Number of stored sections. Diagnostics and tests.
    pub fn len(&self) -> Result<u64, StoreError> {
        let r = db_err!(self.db.begin_read());
        let t = db_err!(r.open_table(SECTIONS));
        Ok(db_err!(t.len()))
    }

    pub fn is_empty(&self) -> Result<bool, StoreError> {
        Ok(self.len()? == 0)
    }
}

/// Cells per axis, re-exported so callers sizing buffers do not reach into `section`.
pub const SECTION_EDGE: usize = EDGE;

/// Re-exported so callers need not reach into `section` for the reserved air id.
pub const AIR_ID: BlockId = AIR;
