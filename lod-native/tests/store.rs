//! Store round-trips and pyramid maintenance.
//!
//! An integration test rather than a unit test because it exercises a real database file — the
//! encoding is only interesting if it survives an actual write and read.

use lod_native::section::{flags, BlockId, Section, AIR, EDGE};
use lod_native::store::{Store, MAX_LEVEL};

/// Interns the block types these tests use, so ids come from the store rather than being invented.
/// Returns (stone, glass, water) — glass is non-air but NOT drawable, the case that breaks folding.
fn blocks(store: &Store) -> (BlockId, BlockId, BlockId) {
    (
        store.intern_block("minecraft:stone", flags::OPAQUE).unwrap(),
        store.intern_block("minecraft:glass", 0).unwrap(),
        store.intern_block("minecraft:water", flags::WATER).unwrap(),
    )
}

/// A temporary database that removes itself, so tests never share state or leave files behind.
struct TempStore {
    store: Store,
    path: std::path::PathBuf,
}

impl TempStore {
    fn new(tag: &str) -> Self {
        let mut path = std::env::temp_dir();
        path.push(format!(
            "mia-lods-test-{tag}-{}-{:?}.redb",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let _ = std::fs::remove_file(&path);
        let store = Store::open(&path).expect("open");
        TempStore { store, path }
    }
}

impl Drop for TempStore {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

fn solid(id: BlockId) -> Section {
    let mut s = Section::air();
    for y in 0..EDGE {
        for z in 0..EDGE {
            for x in 0..EDGE {
                s.set(x, y, z, id);
            }
        }
    }
    s
}

#[test]
fn missing_is_none_not_air() {
    // "Never seen" and "seen and empty" are different answers, and a consumer needs to tell them
    // apart — one means keep looking, the other means there is genuinely nothing there.
    let t = TempStore::new("missing");
    let (stone, glass, water) = blocks(&t.store);
    assert!(t.store.get(0, 1, 2, 3).unwrap().is_none());
    t.store.put(0, 1, 2, 3, &Section::air()).unwrap();
    assert!(t.store.get(0, 1, 2, 3).unwrap().is_some());
}

#[test]
fn round_trips_a_mixed_section() {
    let t = TempStore::new("mixed");
    let (stone, glass, water) = blocks(&t.store);
    let mut s = Section::air();
    s.set(0, 0, 0, stone);
    s.set(5, 6, 7, water);
    s.set(15, 15, 15, glass);
    t.store.put(0, -4, 9, -12345, &s).unwrap();
    let back = t.store.get(0, -4, 9, -12345).unwrap().expect("stored");
    assert_eq!(back.get(0, 0, 0), stone);
    assert_eq!(back.get(5, 6, 7), water);
    assert_eq!(back.get(15, 15, 15), glass);
    assert_eq!(back.get(1, 1, 1), AIR);
}

#[test]
fn round_trips_a_solid_section() {
    let t = TempStore::new("solid");
    let (stone, glass, water) = blocks(&t.store);
    let s = solid(stone);
    t.store.put(0, 0, 0, 0, &s).unwrap();
    let back = t.store.get(0, 0, 0, 0).unwrap().unwrap();
    for y in (0..EDGE).step_by(5) {
        for z in (0..EDGE).step_by(5) {
            for x in (0..EDGE).step_by(5) {
                assert_eq!(back.get(x, y, z), stone, "at ({x},{y},{z})");
            }
        }
    }
}

#[test]
fn negative_coordinates_do_not_collide() {
    // The key packs signed coordinates. A sloppy cast makes -1 and some large positive value share
    // a key, which shows up as terrain from one place appearing in another.
    let t = TempStore::new("neg");
    let (stone, glass, water) = blocks(&t.store);
    t.store.put(0, -1, -1, -1, &solid(stone)).unwrap();
    t.store.put(0, 1, 1, 1, &solid(water)).unwrap();
    assert_eq!(t.store.get(0, -1, -1, -1).unwrap().unwrap().get(0, 0, 0), stone);
    assert_eq!(t.store.get(0, 1, 1, 1).unwrap().unwrap().get(0, 0, 0), water);
    assert!(t.store.get(0, -1, 1, -1).unwrap().is_none());
}

#[test]
fn levels_are_separate_keyspaces() {
    let t = TempStore::new("levels");
    let (stone, glass, water) = blocks(&t.store);
    t.store.put(0, 7, 7, 7, &solid(stone)).unwrap();
    t.store.put(3, 7, 7, 7, &solid(water)).unwrap();
    assert_eq!(t.store.get(0, 7, 7, 7).unwrap().unwrap().get(0, 0, 0), stone);
    assert_eq!(t.store.get(3, 7, 7, 7).unwrap().unwrap().get(0, 0, 0), water);
}

#[test]
fn air_sections_cost_almost_nothing() {
    // The storage assumption worth protecting: most of the world is air, and storing 8 KB of zeroes
    // per empty section would dominate the database.
    let t = TempStore::new("aircost");
    let (stone, glass, water) = blocks(&t.store);
    let before = std::fs::metadata(&t.path).map(|m| m.len()).unwrap_or(0);
    for i in 0..200 {
        t.store.put(0, i, 0, 0, &Section::air()).unwrap();
    }
    let after = std::fs::metadata(&t.path).map(|m| m.len()).unwrap_or(0);
    let per_section = (after.saturating_sub(before)) / 200;
    assert!(per_section < 512, "{per_section} bytes per air section is not a marker");
}

#[test]
fn indexing_builds_the_whole_pyramid() {
    let t = TempStore::new("pyramid");
    let (stone, glass, water) = blocks(&t.store);
    t.store.index(0, 0, 0, &solid(stone)).unwrap();
    t.store.flush().unwrap();
    for level in 0..=MAX_LEVEL {
        let s = t.store.get(level, 0, 0, 0).unwrap();
        assert!(s.is_some(), "level {level} missing from the pyramid");
        assert_eq!(s.unwrap().get(0, 0, 0), stone, "level {level} lost the terrain");
    }
    // Nothing beyond the maintained depth.
    assert!(t.store.get(MAX_LEVEL + 1, 0, 0, 0).unwrap().is_none());
}

#[test]
fn a_coarse_level_keeps_terrain_from_a_single_child() {
    // A parent assembled from ONE known child must still show that child's terrain in its octant.
    // Getting this wrong yields coarse levels that are blank until a region is fully explored.
    let t = TempStore::new("onechild");
    let (stone, glass, water) = blocks(&t.store);
    t.store.index(1, 1, 1, &solid(stone)).unwrap();
    t.store.flush().unwrap();
    let parent = t.store.get(1, 0, 0, 0).unwrap().expect("level 1");
    let half = EDGE / 2;
    assert_eq!(parent.get(half, half, half), stone, "the known octant must survive");
    assert_eq!(parent.get(0, 0, 0), AIR, "unknown octants stay air");
}

#[test]
fn reindexing_a_child_updates_its_parents() {
    // Terrain changes. A parent still describing the old contents is the kind of staleness nobody
    // notices until the map disagrees with the world.
    let t = TempStore::new("reindex");
    let (stone, glass, water) = blocks(&t.store);
    t.store.index(0, 0, 0, &solid(stone)).unwrap();
    t.store.flush().unwrap();
    assert_eq!(t.store.get(2, 0, 0, 0).unwrap().unwrap().get(0, 0, 0), stone);
    t.store.index(0, 0, 0, &solid(water)).unwrap();
    t.store.flush().unwrap();
    assert_eq!(
        t.store.get(2, 0, 0, 0).unwrap().unwrap().get(0, 0, 0),
        water,
        "the pyramid kept a stale parent"
    );
}

#[test]
fn drawable_terrain_survives_every_level() {
    // The folding rule, end to end through storage: a child riddled with invisible non-air must not
    // dissolve into holes as it is folded upward.
    let t = TempStore::new("drawable");
    let (stone, glass, water) = blocks(&t.store);
    let mut child = Section::air();
    for y in 0..EDGE {
        for z in 0..EDGE {
            for x in 0..EDGE {
                child.set(x, y, z, if (x + y + z) % 2 == 0 { stone } else { glass });
            }
        }
    }
    t.store.index(0, 0, 0, &child).unwrap();
    t.store.flush().unwrap();
    for level in 1..=MAX_LEVEL {
        let s = t.store.get(level, 0, 0, 0).unwrap().unwrap();
        assert_eq!(s.get(0, 0, 0), stone, "level {level} dissolved into air");
    }
}

#[test]
fn data_survives_reopening() {
    // Persistence is the entire point; an in-memory store that forgets on restart is useless.
    let mut path = std::env::temp_dir();
    path.push(format!("mia-lods-reopen-{}.redb", std::process::id()));
    let _ = std::fs::remove_file(&path);
    {
        let s = Store::open(&path).unwrap();
        let (stone, _, _) = blocks(&s);
        s.index(3, 4, 5, &solid(stone)).unwrap();
        s.flush().unwrap();
    }
    {
        let s = Store::open(&path).unwrap();
        // Ids must survive the reopen too — that is the point of persisting the block table.
        let stone = s.intern_block("minecraft:stone", flags::OPAQUE).unwrap();
        assert_eq!(s.get(0, 3, 4, 5).unwrap().unwrap().get(0, 0, 0), stone);
        assert!(s.get(1, 1, 2, 2).unwrap().is_some(), "pyramid survived too");
    }
    let _ = std::fs::remove_file(&path);
}

// ---- batching and crash safety -----------------------------------------------------------------

#[test]
fn indexing_is_cheap_and_defers_the_fold() {
    // index() must neither fold nor commit. Folding per section means reading eight children at
    // every level on every write; committing per section churns the copy-on-write B-tree so hard
    // that 28 MB of data occupied a 97 MB file.
    let t = TempStore::new("defer");
    let (stone, glass, water) = blocks(&t.store);
    t.store.index(0, 0, 0, &solid(stone)).unwrap();
    assert!(t.store.get(1, 0, 0, 0).unwrap().is_none(), "index() folded eagerly");
    assert!(t.store.get(0, 0, 0, 0).unwrap().is_none(), "index() committed eagerly");
    t.store.flush().unwrap();
    assert!(t.store.get(1, 0, 0, 0).unwrap().is_some(), "flush() did not fold");
    assert_eq!(t.store.dirty_count().unwrap(), 0, "flush left markers behind");
}

#[test]
fn siblings_share_one_rebuild() {
    // The point of batching: eight children of the same parent must cost ONE fold of that parent,
    // not eight. Without this the deferral saves nothing.
    let t = TempStore::new("share");
    let (stone, glass, water) = blocks(&t.store);
    for dz in 0..2 {
        for dy in 0..2 {
            for dx in 0..2 {
                t.store.index(dx, dy, dz, &solid(stone)).unwrap();
            }
        }
    }
    // Eight siblings share one level-1 parent, and that parent's ancestors are shared too. Markers
    // are buffered with the sections, so commit first to see them.
    t.store.commit_pending().unwrap();
    assert_eq!(t.store.dirty_count().unwrap(), MAX_LEVEL as u64,
               "siblings should collapse to one marker per level");
    let rebuilt = t.store.flush().unwrap();
    assert_eq!(rebuilt, MAX_LEVEL as usize, "expected one rebuild per level, got {rebuilt}");
}

#[test]
fn committed_work_survives_a_crash_but_buffered_work_does_not() {
    // The durability contract, stated rather than assumed. A section is durable once COMMITTED —
    // when a batch fills or on flush — not the moment index() returns. Buffering is what keeps the
    // copy-on-write B-tree from churning, and the cost is a small window of sections that are
    // simply re-indexed the next time those chunks load.
    //
    // What must survive a crash is that COMMITTED sections carry their dirty markers with them, so
    // the pyramid is never silently stale.
    let mut path = std::env::temp_dir();
    path.push(format!("mia-lods-crash-{}.redb", std::process::id()));
    let _ = std::fs::remove_file(&path);
    {
        let s = Store::open(&path).unwrap();
        let (stone, _, _) = blocks(&s);
        s.index(9, 9, 9, &solid(stone)).unwrap();
        s.commit_pending().unwrap();
        // Committed but NOT flushed: the fold is still outstanding. Drop as though the process died.
    }
    {
        let s = Store::open(&path).unwrap();
        let stone = s.intern_block("minecraft:stone", flags::OPAQUE).unwrap();
        assert!(s.dirty_count().unwrap() > 0, "pending rebuilds were lost");
        s.flush().unwrap();
        // Child (9,9,9) is odd on every axis, so it folds into octant (1,1,1) of parent (4,4,4) —
        // the far corner, not the origin.
        let parent = s.get(1, 4, 4, 4).unwrap().expect("parent rebuilt after recovery");
        let half = EDGE / 2;
        assert_eq!(parent.get(half, half, half), stone,
                   "recovered flush did not rebuild the parent");
    }
    let _ = std::fs::remove_file(&path);
}

#[test]
fn coarse_levels_fold_refreshed_children_not_stale_ones() {
    // Ordering matters: level 2 must fold a level 1 that this same flush has already refreshed. Fold
    // coarsest-first and the pyramid comes out self-consistent and wrong, which is undetectable
    // later.
    let t = TempStore::new("order");
    let (stone, glass, water) = blocks(&t.store);
    t.store.index(0, 0, 0, &solid(stone)).unwrap();
    t.store.flush().unwrap();
    t.store.index(0, 0, 0, &solid(water)).unwrap();
    t.store.flush().unwrap();
    for level in 1..=MAX_LEVEL {
        assert_eq!(t.store.get(level, 0, 0, 0).unwrap().unwrap().get(0, 0, 0), water,
                   "level {level} folded a stale child");
    }
}

#[test]
fn flush_on_a_clean_store_is_a_no_op() {
    let t = TempStore::new("noop");
    let (stone, glass, water) = blocks(&t.store);
    assert_eq!(t.store.flush().unwrap(), 0);
    t.store.index(0, 0, 0, &solid(stone)).unwrap();
    t.store.flush().unwrap();
    assert_eq!(t.store.flush().unwrap(), 0, "a second flush should have nothing to do");
}

// ---- the block table: what makes stored ids stable ----------------------------------------------

#[test]
fn interning_is_stable_and_reversible() {
    let t = TempStore::new("intern");
    let a = t.store.intern_block("minecraft:stone", flags::OPAQUE).unwrap();
    let b = t.store.intern_block("minecraft:stone", flags::OPAQUE).unwrap();
    assert_eq!(a, b, "the same key must keep the same id");
    assert_ne!(a, AIR, "air is reserved and must never be handed out");
    let (f, key) = t.store.block(a).unwrap().expect("registered");
    assert_eq!(key, "minecraft:stone");
    assert_eq!(f, flags::OPAQUE);
}

#[test]
fn distinct_blocks_get_distinct_ids() {
    let t = TempStore::new("distinct");
    let (stone, glass, water) = blocks(&t.store);
    assert_ne!(stone, glass);
    assert_ne!(glass, water);
    assert_ne!(stone, water);
}

#[test]
fn ids_survive_reopening() {
    // The whole reason ids are anchored to a canonical string: a game's runtime ids move with the
    // version and mod set, so stored terrain would be silently reinterpreted if we used those.
    let mut path = std::env::temp_dir();
    path.push(format!("mia-lods-ids-{}.redb", std::process::id()));
    let _ = std::fs::remove_file(&path);
    let first = {
        let s = Store::open(&path).unwrap();
        s.intern_block("minecraft:deepslate", flags::OPAQUE).unwrap()
    };
    {
        let s = Store::open(&path).unwrap();
        let again = s.intern_block("minecraft:deepslate", flags::OPAQUE).unwrap();
        assert_eq!(first, again, "an id changed across a reopen — stored terrain would shift");
        assert_eq!(s.block(first).unwrap().unwrap().1, "minecraft:deepslate");
    }
    let _ = std::fs::remove_file(&path);
}

#[test]
fn reclassifying_a_block_does_not_need_reindexing() {
    // A consumer that refines its opacity classification should be able to say so without
    // re-indexing the world, which is the main advantage of storing identity over appearance.
    let t = TempStore::new("reclass");
    let id = t.store.intern_block("minecraft:leaves", 0).unwrap();
    t.store.index(0, 0, 0, &solid(id)).unwrap();
    t.store.flush().unwrap();
    // Folded as non-drawable: nothing else in the group, so it survives as the fallback.
    assert_eq!(t.store.get(1, 0, 0, 0).unwrap().unwrap().get(0, 0, 0), id);

    let same = t.store.intern_block("minecraft:leaves", flags::OPAQUE).unwrap();
    assert_eq!(same, id, "reclassifying must not mint a new id");
    assert_eq!(t.store.block(id).unwrap().unwrap().0, flags::OPAQUE, "flags did not update");
}

#[test]
fn folding_prefers_a_drawable_block_over_a_transparent_one() {
    // End to end through storage, with flags coming from the block table rather than a test stub:
    // glass above stone must fold to stone, or surfaces fill with holes at every coarse level.
    let t = TempStore::new("folddraw");
    let (stone, glass, _) = blocks(&t.store);
    let mut child = Section::air();
    for y in 0..EDGE {
        for z in 0..EDGE {
            for x in 0..EDGE {
                child.set(x, y, z, if y % 2 == 0 { stone } else { glass });
            }
        }
    }
    t.store.index(0, 0, 0, &child).unwrap();
    t.store.flush().unwrap();
    for level in 1..=MAX_LEVEL {
        assert_eq!(t.store.get(level, 0, 0, 0).unwrap().unwrap().get(0, 0, 0), stone,
                   "level {level} chose the transparent block");
    }
}

#[test]
fn the_block_table_can_be_enumerated_for_a_consumer_mapping() {
    let t = TempStore::new("enumerate");
    let (stone, _, _) = blocks(&t.store);
    let all = t.store.blocks().unwrap();
    assert!(all.len() >= 3, "expected every interned block");
    assert!(all.iter().any(|(id, f, k)| *id == stone && *f == flags::OPAQUE && k == "minecraft:stone"));
}

// ---- bit-packed indices -------------------------------------------------------------------------

#[test]
fn index_width_follows_the_palette() {
    use lod_native::store::index_bits;
    assert_eq!(index_bits(0), 0);
    assert_eq!(index_bits(1), 0, "a uniform section needs no index data at all");
    assert_eq!(index_bits(2), 1);
    assert_eq!(index_bits(3), 2);
    assert_eq!(index_bits(4), 2);
    assert_eq!(index_bits(5), 3);
    assert_eq!(index_bits(256), 8);
    assert_eq!(index_bits(257), 9);
}

#[test]
fn packed_sections_round_trip_at_every_width() {
    // Widths are where an off-by-one hides: 2 palette entries is 1 bit, 3 is 2, 5 is 3. A section
    // that packs but unpacks wrong would look like terrain, just the wrong terrain.
    let t = TempStore::new("widths");
    for distinct in [1usize, 2, 3, 5, 9, 17, 40] {
        let ids: Vec<BlockId> = (0..distinct)
            .map(|i| t.store.intern_block(&format!("test:block_{i}"), flags::OPAQUE).unwrap())
            .collect();
        let mut s = Section::air();
        for y in 0..EDGE {
            for z in 0..EDGE {
                for x in 0..EDGE {
                    s.set(x, y, z, ids[(x + y * 3 + z * 7) % distinct]);
                }
            }
        }
        t.store.put(0, distinct as i32, 0, 0, &s).unwrap();
        let back = t.store.get(0, distinct as i32, 0, 0).unwrap().expect("stored");
        for y in 0..EDGE {
            for z in 0..EDGE {
                for x in 0..EDGE {
                    assert_eq!(back.get(x, y, z), s.get(x, y, z),
                               "mismatch at ({x},{y},{z}) with {distinct} distinct blocks");
                }
            }
        }
    }
}

#[test]
fn a_uniform_section_stores_no_index_data() {
    // The best case, and the common one underground: one block type means nothing to choose
    // between, so only the palette entry is worth storing.
    let t = TempStore::new("uniform");
    let (stone, _, _) = blocks(&t.store);
    t.store.put(0, 0, 0, 0, &solid(stone)).unwrap();
    let sizes = t.store.value_sizes().unwrap();
    let largest = sizes.iter().map(|(sz, _)| *sz).max().unwrap();
    assert!(largest < 32, "a uniform section took {largest} bytes; it should be palette-only");
    assert_eq!(t.store.get(0, 0, 0, 0).unwrap().unwrap().get(5, 5, 5), stone);
}

#[test]
fn packing_beats_the_previous_encoding_on_realistic_data() {
    // A section with a handful of block types is the normal case — median 3 measured in the wild.
    // Sixteen bits per cell spent fourteen of them on zero padding.
    let t = TempStore::new("beats");
    let (stone, glass, water) = blocks(&t.store);
    let mut s = Section::air();
    for y in 0..EDGE {
        for z in 0..EDGE {
            for x in 0..EDGE {
                s.set(x, y, z, match (x + y + z) % 3 { 0 => stone, 1 => glass, _ => water });
            }
        }
    }
    t.store.put(0, 0, 0, 0, &s).unwrap();
    let stored = t.store.value_sizes().unwrap().iter().map(|(sz, _)| *sz).max().unwrap();
    // Four distinct ids (incl. air) is 2 bits: 4096 cells = 1024 bytes before compression, against
    // 8192 at the old width.
    assert!(stored < 1200, "packed section took {stored} bytes, expected well under the u16 form");
}

// ---- skipping unchanged rewrites ----------------------------------------------------------------

#[test]
fn reindexing_identical_terrain_is_skipped() {
    // Measured at 48% of writes in a real session: chunks reload as a player moves and the same
    // terrain is re-encoded and rewritten. Each rewrite churns the copy-on-write B-tree.
    let t = TempStore::new("dedup");
    let (stone, _, _) = blocks(&t.store);
    let s = solid(stone);
    t.store.index(0, 0, 0, &s).unwrap();
    t.store.flush().unwrap();
    assert_eq!(t.store.skipped(), 0);

    for _ in 0..10 {
        t.store.index(0, 0, 0, &s).unwrap();
    }
    assert_eq!(t.store.skipped(), 10, "identical rewrites should not reach the database");
}

#[test]
fn changed_terrain_is_never_skipped() {
    // The dangerous half: a section that DID change must be written, or the map shows terrain that
    // no longer exists and nothing detects it.
    let t = TempStore::new("dedupchange");
    let (stone, glass, water) = blocks(&t.store);
    t.store.index(0, 0, 0, &solid(stone)).unwrap();
    t.store.flush().unwrap();

    t.store.index(0, 0, 0, &solid(water)).unwrap();
    t.store.flush().unwrap();
    assert_eq!(t.store.get(0, 0, 0, 0).unwrap().unwrap().get(0, 0, 0), water,
               "a changed section was skipped");

    // And one differing by a single cell, which a coarse hash could miss.
    let mut nearly = solid(water);
    nearly.set(7, 7, 7, glass);
    t.store.index(0, 0, 0, &nearly).unwrap();
    t.store.flush().unwrap();
    assert_eq!(t.store.get(0, 0, 0, 0).unwrap().unwrap().get(7, 7, 7), glass,
               "a one-cell change was skipped");
}

#[test]
fn skipping_does_not_suppress_the_fold() {
    // A skipped write must leave the pyramid alone rather than marking parents dirty for nothing —
    // otherwise every reload would schedule folds that change nothing.
    let t = TempStore::new("dedupfold");
    let (stone, _, _) = blocks(&t.store);
    t.store.index(0, 0, 0, &solid(stone)).unwrap();
    t.store.flush().unwrap();
    assert_eq!(t.store.dirty_count().unwrap(), 0);

    t.store.index(0, 0, 0, &solid(stone)).unwrap();
    t.store.commit_pending().unwrap();
    assert_eq!(t.store.dirty_count().unwrap(), 0,
               "a skipped write should not dirty the pyramid");
}

#[test]
fn different_sections_with_the_same_contents_are_both_written() {
    // Dedup is keyed per section, not by content globally: two places can hold identical terrain and
    // both must exist.
    let t = TempStore::new("dedupkeys");
    let (stone, _, _) = blocks(&t.store);
    t.store.index(0, 0, 0, &solid(stone)).unwrap();
    t.store.index(5, 0, 0, &solid(stone)).unwrap();
    t.store.flush().unwrap();
    assert_eq!(t.store.skipped(), 0, "distinct coordinates must not dedup against each other");
    assert!(t.store.get(0, 0, 0, 0).unwrap().is_some());
    assert!(t.store.get(0, 5, 0, 0).unwrap().is_some());
}

// ---- biomes -------------------------------------------------------------------------------------

#[test]
fn biomes_round_trip() {
    use lod_native::section::BIOME_CELLS;
    let t = TempStore::new("biomes");
    let (stone, _, _) = blocks(&t.store);
    let plains = t.store.intern_block("biome:minecraft:plains", 0).unwrap();
    let forest = t.store.intern_block("biome:minecraft:forest", 0).unwrap();

    let mut s = solid(stone);
    let mut b = vec![plains; BIOME_CELLS];
    b[0] = forest;
    b[BIOME_CELLS - 1] = forest;
    s.set_biomes(&b);

    t.store.put(0, 0, 0, 0, &s).unwrap();
    let back = t.store.get(0, 0, 0, 0).unwrap().expect("stored");
    assert_eq!(back.biomes().len(), BIOME_CELLS, "biomes were lost in storage");
    assert_eq!(back.biomes()[0], forest);
    assert_eq!(back.biomes()[1], plains);
    assert_eq!(back.biomes()[BIOME_CELLS - 1], forest);
}

#[test]
fn biome_lookup_maps_block_cells_onto_the_coarser_grid() {
    use lod_native::section::BIOME_CELLS;
    let t = TempStore::new("biomemap");
    let (stone, _, _) = blocks(&t.store);
    let a = t.store.intern_block("biome:a", 0).unwrap();
    let b = t.store.intern_block("biome:b", 0).unwrap();
    let mut s = solid(stone);
    let mut biomes = vec![a; BIOME_CELLS];
    biomes[(0 * 4 + 0) * 4 + 1] = b;      // biome cell x=1 -> block cells x 4..7
    s.set_biomes(&biomes);

    assert_eq!(s.biome_at(0, 0, 0), a);
    assert_eq!(s.biome_at(3, 0, 0), a, "x=3 is still the first biome cell");
    assert_eq!(s.biome_at(4, 0, 0), b, "x=4 crosses into the second");
    assert_eq!(s.biome_at(7, 0, 0), b);
    assert_eq!(s.biome_at(8, 0, 0), a);
}

#[test]
fn sections_without_biomes_still_read() {
    // The 149k sections already indexed carry no biome data. They must keep reading — untinted —
    // rather than being rejected, and correct themselves when that terrain is re-indexed.
    let t = TempStore::new("nobiome");
    let (stone, _, _) = blocks(&t.store);
    t.store.put(0, 0, 0, 0, &solid(stone)).unwrap();
    let back = t.store.get(0, 0, 0, 0).unwrap().expect("stored");
    assert!(back.biomes().is_empty());
    assert_eq!(back.biome_at(0, 0, 0), AIR, "absent biomes read as unknown, not as a real biome");
    assert_eq!(back.get(0, 0, 0), stone, "block data is unaffected");
}

#[test]
fn a_malformed_biome_array_is_ignored_rather_than_stored() {
    let t = TempStore::new("badbiome");
    let (stone, _, _) = blocks(&t.store);
    let mut s = solid(stone);
    s.set_biomes(&[1, 2, 3]);
    assert!(s.biomes().is_empty(), "a wrong-sized array must not become partial biome data");
}
