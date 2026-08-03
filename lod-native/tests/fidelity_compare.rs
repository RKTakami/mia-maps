//! Visual Fidelity & Coarse-Level Comparison Tests for `mia-loddy`.
//!
//! Verifies that coarse LOD folding (`representative()`) preserves solid surface opacity
//! and brightness, preventing "moth-eaten" terrain holes when decorative transparent
//! blocks sit on top of solid ground, and verifies ring boundary continuity.

use lod_native::lod::representative;
use lod_native::section::{flags, BlockId, Drawable, Section};
use lod_native::store::{Store, MAX_LEVEL};

struct StubDrawable {
    opaque_ids: Vec<BlockId>,
}

impl StubDrawable {
    fn new(opaque_ids: Vec<BlockId>) -> Self {
        Self { opaque_ids }
    }
}

impl Drawable for StubDrawable {
    fn is_drawable(&self, id: BlockId) -> bool {
        self.opaque_ids.contains(&id)
    }
}

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

#[test]
fn fidelity_preserves_solid_surface_under_tall_grass() {
    // Simulate a 2x2x2 octant where the lower layer (y=0) is a solid grass block (OPAQUE)
    // and the upper layer (y=1) is tall grass / fern (not OPAQUE, but non-air).
    // Naive "topmost non-air" would pick tall grass, which bakes to transparent/dark
    // from a distance. representative() must pick the OPAQUE grass block.
    let grass_block: BlockId = 10;
    let tall_grass: BlockId = 20;
    let drawable = StubDrawable::new(vec![grass_block]);

    let mut section = Section::air();
    // Fill y=0 with solid grass blocks
    for z in 0..2 {
        for x in 0..2 {
            section.set(x, 0, z, grass_block);
        }
    }
    // Fill y=1 with non-opaque tall grass
    for z in 0..2 {
        for x in 0..2 {
            section.set(x, 1, z, tall_grass);
        }
    }

    let chosen = representative(&section, 0, 0, 0, &drawable);
    assert_eq!(
        chosen, grass_block,
        "Coarse LOD folding must select the OPAQUE surface block over non-opaque decorative foliage"
    );
}

#[test]
fn fidelity_falls_back_to_non_air_when_no_solid_surface_exists() {
    // If an octant contains only water, glass, or leaves (no OPAQUE block),
    // representative() should fall back to the topmost non-air block rather than AIR.
    let water: BlockId = 30;
    let drawable = StubDrawable::new(vec![]); // nothing is OPAQUE

    let mut section = Section::air();
    section.set(0, 1, 0, water);

    let chosen = representative(&section, 0, 0, 0, &drawable);
    assert_eq!(
        chosen, water,
        "When no OPAQUE block is present, folding should fall back to non-air rather than AIR"
    );
}

#[test]
fn fidelity_ring_boundary_continuity_across_levels() {
    // Verify end-to-end through storage that a solid terrain platform remains solid
    // at every coarse LOD level (0..=MAX_LEVEL), preventing vertical gaps at ring boundaries.
    let t = TempStore::new("fidelity_ring_seams");
    let stone = t.store.intern_block("minecraft:stone", flags::OPAQUE).unwrap();

    let mut section = Section::air();
    // Solid platform at y=0..8
    for y in 0..8 {
        for z in 0..16 {
            for x in 0..16 {
                section.set(x, y, z, stone);
            }
        }
    }

    t.store.index(0, 0, 0, &section).unwrap();
    t.store.flush().unwrap();

    for level in 0..=MAX_LEVEL {
        let stored = t.store.get(level, 0, 0, 0).unwrap().expect("Section should exist at level");
        let sample = stored.get(0, 0, 0);
        assert_eq!(
            sample, stone,
            "LOD level {level} must preserve the surface block identity across ring boundaries"
        );
    }
}
