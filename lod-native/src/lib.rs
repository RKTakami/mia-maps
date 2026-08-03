//! mia-lods — a level-of-detail system for Minecraft: index, store, render.
//!
//! Indexes terrain the player has already seen, serves it back at any resolution without the chunks
//! being loaded, and draws it in-world past vanilla render distance.
//!
//! Rendering uses the fastest path a machine offers — compute meshing and GPU-driven draws where
//! available — over a complete GL 4.1 baseline that always works, so no capability is ever a
//! requirement and no machine is excluded.
//!
//! **Cells hold block identity, not colour.** Colour, per-face shading and biome tint are
//! presentation, derived at read time, so how blocks look can change without re-indexing terrain
//! the player has already explored.
//!
//! See `docs/DESIGN.md`.

pub mod cascade;
pub mod jni_api;
pub mod lod;
pub mod mesher;
pub mod section;
pub mod store;
pub mod vulkan;

pub use vulkan::*;

#[cfg(test)]
mod tests {
    use crate::lod::{build_parent, representative, Octant};
    use crate::section::{BlockId, Section, AIR, CELLS, EDGE};

    // Ids are assigned by the store; tests pick their own and say which are drawable.
    const STONE: BlockId = 1;
    const GLASS: BlockId = 2; // non-air, NOT drawable — the cell that breaks naive folding
    const WATER: BlockId = 3;

    /// Only stone blocks sight here.
    fn drawable(id: BlockId) -> bool {
        id == STONE
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
    fn air_section_reads_as_air() {
        let s = Section::air();
        assert!(s.is_air());
        assert_eq!(s.get(0, 0, 0), AIR);
        assert_eq!(s.palette_len(), 1);
    }

    #[test]
    fn set_and_get_round_trips() {
        let mut s = Section::air();
        s.set(1, 2, 3, STONE);
        assert_eq!(s.get(1, 2, 3), STONE);
        assert_eq!(s.get(3, 2, 1), AIR);
        assert!(!s.is_air());
    }

    #[test]
    fn palette_stays_small_for_uniform_sections() {
        // The compression assumption: a solid section is one palette entry, not 4096.
        let mut s = solid(STONE);
        assert_eq!(s.palette_len(), 2, "air + stone");
        s.compact();
        assert_eq!(s.palette_len(), 1, "air is unreferenced after a full overwrite");
    }

    #[test]
    fn compact_preserves_contents() {
        let mut s = Section::air();
        s.set(0, 0, 0, STONE);
        s.set(1, 0, 0, WATER);
        s.set(0, 0, 0, WATER); // stone now unreferenced
        s.compact();
        assert_eq!(s.get(0, 0, 0), WATER);
        assert_eq!(s.get(1, 0, 0), WATER);
        assert_eq!(s.get(2, 0, 0), AIR);
    }

    // ---- the representative choice: the part that produces holes when wrong ----

    #[test]
    fn representative_prefers_drawable_over_other_non_air() {
        // A 2x2x2 straddling a surface: glass sits ABOVE stone. Choosing "topmost non-air" yields
        // the glass and the surface loses that cell; do it across a level and terrain looks
        // moth-eaten from a distance.
        let mut c = Section::air();
        c.set(0, 1, 0, GLASS); // higher, non-air, not drawable
        c.set(0, 0, 0, STONE); // lower, drawable
        assert_eq!(representative(&c, 0, 0, 0, &drawable), STONE,
                   "must pick the drawable child, not the topmost non-air one");
    }

    #[test]
    fn representative_falls_back_to_non_air_when_nothing_is_drawable() {
        let mut c = Section::air();
        c.set(1, 0, 1, WATER);
        assert_eq!(representative(&c, 0, 0, 0, &drawable), WATER);
    }

    #[test]
    fn representative_is_air_only_when_everything_is_air() {
        let c = Section::air();
        assert_eq!(representative(&c, 0, 0, 0, &drawable), AIR);
    }

    #[test]
    fn representative_prefers_the_higher_of_two_drawables() {
        // Looked at from above, the higher surface is the visible one.
        let mut c = Section::air();
        c.set(0, 0, 0, STONE);
        c.set(0, 1, 0, STONE);
        assert_eq!(representative(&c, 0, 0, 0, &drawable), STONE);
    }

    #[test]
    fn an_unknown_id_is_treated_as_not_drawable_rather_than_panicking() {
        // Ids can outlive their table entry if a store is edited out of band. Folding must degrade,
        // not crash — and must still prefer a known-drawable neighbour.
        let unknown: BlockId = 999;
        let mut c = Section::air();
        c.set(0, 1, 0, unknown);
        c.set(0, 0, 0, STONE);
        assert_eq!(representative(&c, 0, 0, 0, &drawable), STONE);
    }

    #[test]
    fn a_solid_child_yields_a_solid_octant_not_a_holey_one() {
        // Fold a fully solid child riddled with non-drawable blocks: every parent cell in its octant
        // must still be solid. Holes here are invisible in a single-cell test but obvious in game.
        let mut child = Section::air();
        for y in 0..EDGE {
            for z in 0..EDGE {
                for x in 0..EDGE {
                    child.set(x, y, z, if (x + y + z) % 2 == 0 { STONE } else { GLASS });
                }
            }
        }
        let parent = build_parent(&[(Octant::new(0, 0, 0), &child)], &drawable);
        let half = EDGE / 2;
        for y in 0..half {
            for z in 0..half {
                for x in 0..half {
                    assert_eq!(parent.get(x, y, z), STONE,
                               "hole at ({x},{y},{z}) — surface would look moth-eaten");
                }
            }
        }
    }

    #[test]
    fn each_child_lands_in_its_own_octant() {
        let a = solid(STONE);
        let b = solid(WATER);
        let parent = build_parent(
            &[(Octant::new(0, 0, 0), &a), (Octant::new(1, 1, 1), &b)], &drawable);
        let half = EDGE / 2;
        assert_eq!(parent.get(0, 0, 0), STONE);
        assert_eq!(parent.get(half, half, half), WATER);
        // An octant with no child stays air rather than borrowing a neighbour's data.
        assert_eq!(parent.get(half, 0, 0), AIR);
    }

    #[test]
    fn a_partially_explored_parent_is_still_usable() {
        // Missing children must not poison the whole parent — half-explored regions still draw.
        let mut child = Section::air();
        child.set(0, 0, 0, STONE);
        let parent = build_parent(&[(Octant::new(1, 0, 1), &child)], &drawable);
        assert!(!parent.is_air(), "the one known octant must survive");
    }

    // ---- flat-array interop: the shape Java consumes ----

    #[test]
    fn ids_round_trip_through_a_section() {
        let mut s = Section::air();
        s.set(0, 0, 0, STONE);
        s.set(3, 4, 5, WATER);
        s.set(15, 15, 15, GLASS);

        let mut ids = vec![0u32; CELLS];
        assert!(s.to_ids(&mut ids));

        let back = Section::from_ids(&ids).expect("rebuilt");
        assert_eq!(back.get(0, 0, 0), STONE);
        assert_eq!(back.get(3, 4, 5), WATER);
        assert_eq!(back.get(15, 15, 15), GLASS);
        assert_eq!(back.get(9, 9, 9), AIR);
    }

    #[test]
    fn id_conversion_rejects_wrong_sized_buffers() {
        // Java hands us arrays; a size mismatch must be refused rather than half-filled, or a caller
        // gets a section that is silently part stale.
        let s = Section::air();
        let mut small = vec![0u32; 10];
        assert!(!s.to_ids(&mut small));
        assert!(Section::from_ids(&[0u32; 4]).is_none());
    }

    #[test]
    fn a_uniform_section_from_ids_still_palettises() {
        // The compression assumption has to survive the Java boundary too, or every indexed section
        // arrives with a 4096-entry palette.
        let s = Section::from_ids(&vec![STONE; CELLS]).unwrap();
        assert_eq!(s.palette_len(), 1, "uniform input should collapse to one palette entry");
    }

    #[test]
    fn an_all_air_array_makes_an_air_section() {
        let s = Section::from_ids(&vec![AIR; CELLS]).unwrap();
        assert!(s.is_air(), "all-zero input must be recognised as air, not stored as data");
    }
}
