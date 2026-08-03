//! Building coarser levels from finer ones.
//!
//! Level N cells are `2^N` blocks. A level-(N+1) section covers the same cell count over twice the
//! span per axis, so it is assembled from **eight** level-N children, each contributing one octant.

use crate::section::{BlockId, Drawable, Section, AIR, EDGE};

/// Which octant a child occupies in its parent, as 0/1 per axis.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Octant {
    pub x: usize,
    pub y: usize,
    pub z: usize,
}

impl Octant {
    pub fn new(x: usize, y: usize, z: usize) -> Self {
        Octant { x: x & 1, y: y & 1, z: z & 1 }
    }
}

/// Fold one child section into its octant of `parent`.
///
/// Each parent cell covers a 2×2×2 block of child cells and must choose ONE block to represent them.
///
/// **The choice is the whole game.** Picking "the first non-air child" looks obviously right and
/// loses surfaces: a 2×2×2 straddling terrain often contains both a solid block and something
/// non-solid but non-air (glass, leaves, water, a torch), and choosing the wrong one punches a hole
/// in the surface. Do that across a whole level and terrain looks moth-eaten from a distance, worse
/// the further out you go, while solid interiors survive untouched.
///
/// So: prefer a **drawable** child — one whose block type blocks sight — and only fall back to any
/// non-air block when the group has none. Air is chosen only when every child is air.
pub fn fold_child_into(parent: &mut Section, child: &Section, oct: Octant, d: &impl Drawable) {
    let half = EDGE / 2;
    for py in 0..half {
        for pz in 0..half {
            for px in 0..half {
                let id = representative(child, px * 2, py * 2, pz * 2, d);
                parent.set(oct.x * half + px, oct.y * half + py, oct.z * half + pz, id);
            }
        }
    }
}

/// Choose the block representing the 2×2×2 group whose minimum corner is (x, y, z).
///
/// Preference order: topmost drawable, then topmost non-air, then air. "Topmost" because a map is
/// looked at from above — when a column holds both, the higher surface is the one you would see.
pub fn representative(child: &Section, x: usize, y: usize, z: usize, d: &impl Drawable) -> BlockId {
    let mut fallback = AIR;
    // Descend so the first hit at each tier is the highest one.
    for dy in (0..2).rev() {
        for dz in 0..2 {
            for dx in 0..2 {
                let id = child.get(x + dx, y + dy, z + dz);
                if id == AIR {
                    continue;
                }
                if d.is_drawable(id) {
                    return id;
                }
                if fallback == AIR {
                    fallback = id;
                }
            }
        }
    }
    fallback
}

/// Assemble a parent from up to eight children. Missing children leave their octant air, so a
/// partially-explored region still yields a usable coarse section rather than nothing.
pub fn build_parent(children: &[(Octant, &Section)], d: &impl Drawable) -> Section {
    let mut parent = Section::air();
    for (oct, child) in children {
        fold_child_into(&mut parent, child, *oct, d);
    }
    parent.compact();
    parent
}
