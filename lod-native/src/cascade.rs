//! Distance-driven LOD cascade planner and culling engine.
//!
//! Selects the appropriate LOD level for each region based on camera distance,
//! ensuring fine detail near the player and coarse aggregates out to far horizons
//! without spatial gaps or overlap.

/// A planned tile in the visible distance cascade.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct CascadeTile {
    pub level: u8,
    pub section_x: i32,
    pub section_y: i32,
    pub section_z: i32,
}

impl CascadeTile {
    pub fn new(level: u8, section_x: i32, section_y: i32, section_z: i32) -> Self {
        CascadeTile {
            level,
            section_x,
            section_y,
            section_z,
        }
    }

    /// Size of this tile in blocks along each axis (`16 * 2^level`).
    pub fn block_size(&self) -> i32 {
        16 * (1 << self.level)
    }

    /// World-space bounding box `(min_x, min_y, min_z, max_x, max_y, max_z)`.
    pub fn bounds(&self) -> [i32; 6] {
        let size = self.block_size();
        let min_x = self.section_x * size;
        let min_y = self.section_y * size;
        let min_z = self.section_z * size;
        [min_x, min_y, min_z, min_x + size, min_y + size, min_z + size]
    }

    /// Squared Euclidean distance from camera to the center of this tile's bounding box.
    pub fn dist_sq_from(&self, cam_x: f32, cam_y: f32, cam_z: f32) -> f32 {
        let b = self.bounds();
        let cx = (b[0] + b[3]) as f32 * 0.5;
        let cy = (b[1] + b[4]) as f32 * 0.5;
        let cz = (b[2] + b[5]) as f32 * 0.5;

        let dx = cx - cam_x;
        let dy = cy - cam_y;
        let dz = cz - cam_z;
        dx * dx + dy * dy + dz * dz
    }
}

/// Configuration and scheduler for the distance cascade.
#[derive(Clone, Debug, PartialEq)]
pub struct CascadePlanner {
    /// Ring radii in blocks for each level (up to 4 levels).
    /// Default: `[256.0, 768.0, 1792.0, 3840.0]`
    pub ring_radii: [f32; 4],
}

impl Default for CascadePlanner {
    fn default() -> Self {
        CascadePlanner {
            ring_radii: [256.0, 768.0, 1792.0, 3840.0],
        }
    }
}

impl CascadePlanner {
    pub fn new() -> Self {
        CascadePlanner::default()
    }

    /// Returns the target LOD level (`0..=4`) for a tile centered at the given squared distance
    /// from the camera.
    pub fn level_for_dist_sq(&self, dist_sq: f32) -> u8 {
        let dist = dist_sq.sqrt();
        for (lvl, &r) in self.ring_radii.iter().enumerate() {
            if dist <= r {
                return lvl as u8;
            }
        }
        4
    }

    /// Generates a front-to-back sorted list of candidate tiles within `view_distance` blocks
    /// around `(cam_x, cam_y, cam_z)`.
    ///
    /// For level 0, it covers `0 .. ring_radii[0]`.
    /// For level `N`, it covers `ring_radii[N-1] .. ring_radii[N]`.
    pub fn plan(
        &self,
        cam_x: f32,
        cam_y: f32,
        cam_z: f32,
        view_distance: f32,
        min_y: i32,
        max_y: i32,
    ) -> Vec<CascadeTile> {
        let mut tiles = Vec::new();
        let max_dist = view_distance.min(self.ring_radii[3] * 2.0);
        let max_dist_sq = max_dist * max_dist;

        // Iterate through each cascade ring level
        for lvl in 0..=4u8 {
            let size = 16 * (1 << lvl);
            let inner_r = if lvl == 0 { 0.0 } else { self.ring_radii[(lvl - 1) as usize] };
            let outer_r = if lvl < 4 {
                self.ring_radii[lvl as usize].min(max_dist)
            } else {
                max_dist
            };

            if inner_r >= max_dist {
                break;
            }

            let inner_sq = inner_r * inner_r;
            let outer_sq = outer_r * outer_r;

            // Bounds in section coordinates for this level
            let min_sx = ((cam_x - outer_r).floor() as i32) / size;
            let max_sx = ((cam_x + outer_r).ceil() as i32) / size;
            let min_sz = ((cam_z - outer_r).floor() as i32) / size;
            let max_sz = ((cam_z + outer_r).ceil() as i32) / size;

            let min_sy = (min_y / size).max(-64);
            let max_sy = (max_y / size).min(64);

            for sy in min_sy..=max_sy {
                for sz in min_sz..=max_sz {
                    for sx in min_sx..=max_sx {
                        let tile = CascadeTile::new(lvl, sx, sy, sz);
                        let d_sq = tile.dist_sq_from(cam_x, cam_y, cam_z);
                        if d_sq >= inner_sq && d_sq <= outer_sq && d_sq <= max_dist_sq {
                            tiles.push(tile);
                        }
                    }
                }
            }
        }

        // Sort front-to-back from camera for occlusion-friendly drawing order
        tiles.sort_by(|a, b| {
            let da = a.dist_sq_from(cam_x, cam_y, cam_z);
            let db = b.dist_sq_from(cam_x, cam_y, cam_z);
            da.partial_cmp(&db).unwrap_or(std::cmp::Ordering::Equal)
        });

        tiles
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn level_selection_respects_ring_radii() {
        let p = CascadePlanner::default();
        assert_eq!(p.level_for_dist_sq(100.0 * 100.0), 0);
        assert_eq!(p.level_for_dist_sq(300.0 * 300.0), 1);
        assert_eq!(p.level_for_dist_sq(1000.0 * 1000.0), 2);
        assert_eq!(p.level_for_dist_sq(2500.0 * 2500.0), 3);
        assert_eq!(p.level_for_dist_sq(5000.0 * 5000.0), 4);
    }

    #[test]
    fn plan_generates_sorted_tiles() {
        let p = CascadePlanner::default();
        let tiles = p.plan(0.0, 64.0, 0.0, 500.0, 0, 128);
        assert!(!tiles.is_empty());
        // Verify front-to-back ordering
        for win in tiles.windows(2) {
            let d0 = win[0].dist_sq_from(0.0, 64.0, 0.0);
            let d1 = win[1].dist_sq_from(0.0, 64.0, 0.0);
            assert!(d0 <= d1, "tiles must be ordered front-to-back from camera");
        }
    }
}
