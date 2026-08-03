//! A section: 16³ cells of palettised block identity.
//!
//! 16³ matches a vanilla chunk section exactly, so indexing is 1:1 with what the game hands us — no
//! stitching, no partial sections, no translation at capture time.
//!
//! **Cells hold block identity, not colour.** Colour, per-face shading and biome tint are
//! presentation: they are derived at read time by the consumer, so how blocks look can change
//! without re-indexing terrain the player has already explored. See `docs/DESIGN.md`.

/// Cells per axis. Matches a Minecraft chunk section.
pub const EDGE: usize = 16;
/// Cells per section.
pub const CELLS: usize = EDGE * EDGE * EDGE;

/// A stable identifier for one block state, assigned by the store and persisted alongside the block
/// state's canonical string. Stable across sessions and game versions, which a runtime id is not.
pub type BlockId = u32;

/// Reserved: nothing here. Never interned, so an all-zero section is trivially empty.
pub const AIR: BlockId = 0;

/// Biome cells per axis. Minecraft resolves biomes at 4x4x4 within a section, so this mirrors it —
/// 64 values against 4096 block cells, which is what makes storing them nearly free.
pub const BIOME_EDGE: usize = 4;
/// Biome cells per section.
pub const BIOME_CELLS: usize = BIOME_EDGE * BIOME_EDGE * BIOME_EDGE;

/// What the *store* knows about a block type, as opposed to what it looks like.
///
/// Held once per block type rather than per cell — 4096 cells share one entry — and consulted when
/// folding coarse levels, which has to know what is visible.
pub mod flags {
    /// Blocks sight. Drives surface detection and what a coarse cell chooses to represent.
    pub const OPAQUE: u16 = 1 << 0;
    /// Water or waterlogged: consumers shade depth through it rather than treating it as terrain.
    pub const WATER: u16 = 1 << 1;
    /// Takes biome tint — grass, leaves, vines. The tint itself is applied at read time.
    pub const FOLIAGE: u16 = 1 << 2;
}

/// Answers "can this block be seen", for code that has ids but not the block table.
///
/// Folding needs this and nothing else, so it is a one-method trait rather than a dependency on the
/// whole store — which also keeps [`crate::lod`] testable with a hand-written table.
pub trait Drawable {
    fn is_drawable(&self, id: BlockId) -> bool;
}

impl<F: Fn(BlockId) -> bool> Drawable for F {
    fn is_drawable(&self, id: BlockId) -> bool {
        self(id)
    }
}

/// A palettised 16³ section of block ids.
///
/// Sections are overwhelmingly uniform — solid stone, or nothing but air — so a per-section palette
/// plus an index array is both smaller and faster to scan than raw ids.
#[derive(Clone, PartialEq, Debug)]
pub struct Section {
    palette: Vec<BlockId>,
    /// One palette index per cell, ordered `(y * EDGE + z) * EDGE + x`.
    indices: Vec<u16>,
    /// Biome id per 4x4x4 cell, ordered `(y * BIOME_EDGE + z) * BIOME_EDGE + x`.
    ///
    /// Stored because the map tints grass, foliage and water **by biome** — without this, most
    /// visible surface terrain renders as raw texture colour instead of the colour it has in world.
    /// Empty means a section indexed before biomes were recorded; it reads as untinted rather than
    /// failing, and corrects itself when that terrain is re-indexed.
    biomes: Vec<BlockId>,
}

impl Section {
    /// An empty section. Stored as a marker rather than an array — most of the world is air.
    pub fn air() -> Self {
        Section { palette: vec![AIR], indices: vec![0; CELLS], biomes: Vec::new() }
    }

    pub fn is_air(&self) -> bool {
        self.palette.iter().all(|&id| id == AIR)
    }

    #[inline]
    pub fn index(x: usize, y: usize, z: usize) -> usize {
        (y * EDGE + z) * EDGE + x
    }

    pub fn get(&self, x: usize, y: usize, z: usize) -> BlockId {
        self.palette[self.indices[Self::index(x, y, z)] as usize]
    }

    pub fn set(&mut self, x: usize, y: usize, z: usize, id: BlockId) {
        let idx = self.intern(id);
        let i = Self::index(x, y, z);
        self.indices[i] = idx;
    }

    /// Palette size. Exposed so tests and callers can assert the compression assumption holds.
    pub fn palette_len(&self) -> usize {
        self.palette.len()
    }

    pub fn palette(&self) -> &[BlockId] {
        &self.palette
    }

    pub fn indices(&self) -> &[u16] {
        &self.indices
    }

    /// Biome ids at 4x4x4 granularity, or empty if this section predates biome recording.
    pub fn biomes(&self) -> &[BlockId] {
        &self.biomes
    }

    /// Attach biome ids. Ignored unless exactly [`BIOME_CELLS`] long, so a malformed caller leaves
    /// the section untinted rather than mis-tinted.
    pub fn set_biomes(&mut self, biomes: &[BlockId]) {
        if biomes.len() == BIOME_CELLS {
            self.biomes = biomes.to_vec();
        }
    }

    /// Biome at a block cell, mapping the 16-cell axis onto the 4-cell biome axis.
    pub fn biome_at(&self, x: usize, y: usize, z: usize) -> BlockId {
        if self.biomes.len() != BIOME_CELLS {
            return AIR;
        }
        let (bx, by, bz) = (x / 4, y / 4, z / 4);
        self.biomes[(by * BIOME_EDGE + bz) * BIOME_EDGE + bx]
    }

    /// Rebuild from stored parts, validating them.
    ///
    /// The boundary where data off disk becomes a `Section`, so it rejects nonsense here rather than
    /// letting an out-of-range index panic in `get()` much later, far from the cause.
    pub fn from_parts(palette: Vec<BlockId>, indices: Vec<u16>) -> Option<Section> {
        if palette.is_empty() || indices.len() != CELLS {
            return None;
        }
        if indices.iter().any(|&i| i as usize >= palette.len()) {
            return None;
        }
        Some(Section { palette, indices, biomes: Vec::new() })
    }

    /// Expand into a flat per-cell array, in the layout Java consumers expect.
    ///
    /// Caller-provided buffer: the map re-reads the same regions constantly while panning, so
    /// allocating 4096 cells per read would churn hard. Returns `false` on a wrong-sized buffer
    /// rather than writing a partial result.
    pub fn to_ids(&self, out: &mut [BlockId]) -> bool {
        if out.len() != CELLS {
            return false;
        }
        for (i, &idx) in self.indices.iter().enumerate() {
            out[i] = self.palette[idx as usize];
        }
        true
    }

    /// Build from a flat per-cell array, palettising as it goes.
    pub fn from_ids(ids: &[BlockId]) -> Option<Section> {
        if ids.len() != CELLS {
            return None;
        }
        let mut s = Section::air();
        for (i, &id) in ids.iter().enumerate() {
            if id == AIR {
                continue; // already air; leaves the palette clean for uniform input
            }
            let idx = s.intern(id);
            s.indices[i] = idx;
        }
        s.compact();
        Some(s)
    }

    fn intern(&mut self, id: BlockId) -> u16 {
        if let Some(i) = self.palette.iter().position(|&p| p == id) {
            return i as u16;
        }
        self.palette.push(id);
        (self.palette.len() - 1) as u16
    }

    /// Drop palette entries no cell references, remapping indices. Worth doing before persisting a
    /// section that has been overwritten repeatedly while indexing.
    pub fn compact(&mut self) {
        let mut used = vec![false; self.palette.len()];
        for &i in &self.indices {
            used[i as usize] = true;
        }
        let mut remap = vec![0u16; self.palette.len()];
        let mut next = Vec::new();
        for (i, keep) in used.iter().enumerate() {
            if *keep {
                remap[i] = next.len() as u16;
                next.push(self.palette[i]);
            }
        }
        for i in self.indices.iter_mut() {
            *i = remap[*i as usize];
        }
        self.palette = next;
    }
}

impl Default for Section {
    fn default() -> Self {
        Self::air()
    }
}
