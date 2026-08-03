//! Greedy meshing engine for 16³ sections.
//!
//! Produces compact, OpenGL 4.1 Core Profile-compatible vertex and index buffers.
//!
//! Capability tiers:
//! - **Baseline (`CpuMesher`)**: Always available, pure Rust CPU greedy meshing.
//! - **Processing seam**: Metal/Vulkan compute backend can implement `Mesher` and produce
//!   identical buffer layouts without altering drawing or JNI consumers.

use crate::section::{BlockId, Section, AIR, EDGE};

/// A strictly aligned, 32-byte terrain vertex compatible with Apple's OpenGL 4.1 Core Profile
/// driver (`4.1 Metal`).
///
/// Layout (32 bytes total, `#[repr(C)]`):
/// - `position` (12 bytes, `[f32; 3]`): local coordinate within the section/tile.
/// - `color`    (4 bytes,  `[u8; 4]`): RGBA tint (normalized in shader).
/// - `uv`       (8 bytes,  `[f32; 2]`): texture coordinate.
/// - `light`    (4 bytes,  `[i16; 2]`): block/sky lightmap coordinates.
/// - `normal`   (3 bytes,  `[i8; 3]`): surface normal `(nx, ny, nz)`.
/// - `flags`    (1 byte,   `u8`): appearance flags / alignment pad to exactly 32 bytes.
#[repr(C)]
#[derive(Copy, Clone, Debug, PartialEq)]
pub struct LodVertex {
    pub position: [f32; 3],
    pub color: [u8; 4],
    pub uv: [f32; 2],
    pub light: [i16; 2],
    pub normal: [i8; 3],
    pub flags: u8,
}

impl LodVertex {
    pub fn new(
        x: f32,
        y: f32,
        z: f32,
        u: f32,
        v: f32,
        nx: i8,
        ny: i8,
        nz: i8,
        color: [u8; 4],
    ) -> Self {
        LodVertex {
            position: [x, y, z],
            color,
            uv: [u, v],
            light: [240, 240], // full light by default for LOD terrain
            normal: [nx, ny, nz],
            flags: 0,
        }
    }
}

/// Meshed geometry buffer for a section or tile.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct MeshBuffer {
    pub vertices: Vec<LodVertex>,
    pub indices: Vec<u32>,
    pub quad_count: usize,
}

impl MeshBuffer {
    pub fn new() -> Self {
        MeshBuffer::default()
    }

    pub fn is_empty(&self) -> bool {
        self.indices.is_empty()
    }

    pub fn clear(&mut self) {
        self.vertices.clear();
        self.indices.clear();
        self.quad_count = 0;
    }

    /// Adds a single quad (4 vertices, 6 indices) to the buffer.
    pub fn push_quad(&mut self, v0: LodVertex, v1: LodVertex, v2: LodVertex, v3: LodVertex) {
        let base = self.vertices.len() as u32;
        self.vertices.extend_from_slice(&[v0, v1, v2, v3]);
        // Triangle 1: [0, 1, 2], Triangle 2: [2, 3, 0]
        self.indices.extend_from_slice(&[
            base,
            base + 1,
            base + 2,
            base + 2,
            base + 3,
            base,
        ]);
        self.quad_count += 1;
    }
}

/// Trait for section meshing backends.
pub trait Mesher {
    fn mesh(
        &self,
        section: &Section,
        drawable: &dyn Fn(BlockId) -> bool,
        color_for_id: &dyn Fn(BlockId) -> [u8; 4],
    ) -> MeshBuffer;
}

/// Pure-Rust CPU greedy mesher.
///
/// Merges adjacent coplanar faces of the same block id along X, Y, and Z axes into single
/// rectangular quads. On uniform terrain this reduces vertex count by over 80%.
#[derive(Clone, Debug, Default)]
pub struct CpuMesher;

impl CpuMesher {
    pub fn new() -> Self {
        CpuMesher
    }
}

impl Mesher for CpuMesher {
    fn mesh(
        &self,
        section: &Section,
        drawable: &dyn Fn(BlockId) -> bool,
        color_for_id: &dyn Fn(BlockId) -> [u8; 4],
    ) -> MeshBuffer {
        let mut buffer = MeshBuffer::new();
        if section.is_air() {
            return buffer;
        }

        // 6 face directions: +X, -X, +Y, -Y, +Z, -Z
        // We mesh each axis (0=X, 1=Y, 2=Z) in both positive and negative directions.
        for axis in 0..3 {
            let u_axis = (axis + 1) % 3;
            let v_axis = (axis + 2) % 3;

            for pos_dir in [false, true] {
                // We iterate over the slices perpendicular to `axis`.
                // A slice is a 16x16 mask of visible faces.
                for slice in 0..EDGE {
                    let mut mask = [None; EDGE * EDGE];

                    for v in 0..EDGE {
                        for u in 0..EDGE {
                            let mut coords = [0; 3];
                            coords[axis] = slice;
                            coords[u_axis] = u;
                            coords[v_axis] = v;

                            let id = section.get(coords[0], coords[1], coords[2]);
                            if id == AIR || !drawable(id) {
                                continue;
                            }

                            // Check neighbor cell along axis direction
                            let visible = if pos_dir {
                                if slice + 1 < EDGE {
                                    let n_id = section.get(
                                        if axis == 0 { slice + 1 } else { coords[0] },
                                        if axis == 1 { slice + 1 } else { coords[1] },
                                        if axis == 2 { slice + 1 } else { coords[2] },
                                    );
                                    n_id == AIR || !drawable(n_id)
                                } else {
                                    true // outer face of section
                                }
                            } else {
                                if slice > 0 {
                                    let n_id = section.get(
                                        if axis == 0 { slice - 1 } else { coords[0] },
                                        if axis == 1 { slice - 1 } else { coords[1] },
                                        if axis == 2 { slice - 1 } else { coords[2] },
                                    );
                                    n_id == AIR || !drawable(n_id)
                                } else {
                                    true // outer face of section
                                }
                            };

                            if visible {
                                mask[v * EDGE + u] = Some(id);
                            }
                        }
                    }

                    // Now greedy mesh the 2D mask of 16x16 cells
                    let mut v = 0;
                    while v < EDGE {
                        let mut u = 0;
                        while u < EDGE {
                            let Some(id) = mask[v * EDGE + u] else {
                                u += 1;
                                continue;
                            };

                            // Compute width of quad along u_axis
                            let mut width = 1;
                            while u + width < EDGE && mask[v * EDGE + (u + width)] == Some(id) {
                                width += 1;
                            }

                            // Compute height of quad along v_axis
                            let mut height = 1;
                            'outer: while v + height < EDGE {
                                for du in 0..width {
                                    if mask[(v + height) * EDGE + (u + du)] != Some(id) {
                                        break 'outer;
                                    }
                                }
                                height += 1;
                            }

                            // Clear mask for merged cells
                            for dv in 0..height {
                                for du in 0..width {
                                    mask[(v + dv) * EDGE + (u + du)] = None;
                                }
                            }

                            // Emit quad vertices
                            let mut c0 = [0.0; 3];
                            let mut c1 = [0.0; 3];
                            let mut c2 = [0.0; 3];
                            let mut c3 = [0.0; 3];

                            let s_pos = if pos_dir { (slice + 1) as f32 } else { slice as f32 };
                            let u0 = u as f32;
                            let u1 = (u + width) as f32;
                            let v0 = v as f32;
                            let v1 = (v + height) as f32;

                            c0[axis] = s_pos;
                            c0[u_axis] = u0;
                            c0[v_axis] = v0;

                            c1[axis] = s_pos;
                            c1[u_axis] = u1;
                            c1[v_axis] = v0;

                            c2[axis] = s_pos;
                            c2[u_axis] = u1;
                            c2[v_axis] = v1;

                            c3[axis] = s_pos;
                            c3[u_axis] = u0;
                            c3[v_axis] = v1;

                            let (nx, ny, nz) = match (axis, pos_dir) {
                                (0, true)  => (1, 0, 0),
                                (0, false) => (-1, 0, 0),
                                (1, true)  => (0, 1, 0),
                                (1, false) => (0, -1, 0),
                                (2, true)  => (0, 0, 1),
                                (2, false) => (0, 0, -1),
                                _          => (0, 0, 0),
                            };

                            let color = color_for_id(id);

                            let vtx0 = LodVertex::new(c0[0], c0[1], c0[2], 0.0, 0.0, nx, ny, nz, color);
                            let vtx1 = LodVertex::new(c1[0], c1[1], c1[2], width as f32, 0.0, nx, ny, nz, color);
                            let vtx2 = LodVertex::new(c2[0], c2[1], c2[2], width as f32, height as f32, nx, ny, nz, color);
                            let vtx3 = LodVertex::new(c3[0], c3[1], c3[2], 0.0, height as f32, nx, ny, nz, color);

                            if pos_dir {
                                buffer.push_quad(vtx0, vtx1, vtx2, vtx3);
                            } else {
                                buffer.push_quad(vtx0, vtx3, vtx2, vtx1);
                            }

                            u += width;
                        }
                        v += 1;
                    }
                }
            }
        }

        buffer
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::section::{Section, AIR, EDGE};

    const STONE: BlockId = 1;
    const _GLASS: BlockId = 2;

    fn is_stone(id: BlockId) -> bool {
        id == STONE
    }

    fn default_color(_id: BlockId) -> [u8; 4] {
        [255, 255, 255, 255]
    }

    #[test]
    fn air_section_produces_no_geometry() {
        let mesher = CpuMesher::new();
        let s = Section::air();
        let buf = mesher.mesh(&s, &is_stone, &default_color);
        assert!(buf.is_empty());
        assert_eq!(buf.quad_count, 0);
    }

    #[test]
    fn solid_section_reduces_to_six_quads() {
        let mut s = Section::air();
        for y in 0..EDGE {
            for z in 0..EDGE {
                for x in 0..EDGE {
                    s.set(x, y, z, STONE);
                }
            }
        }

        let mesher = CpuMesher::new();
        let buf = mesher.mesh(&s, &is_stone, &default_color);
        assert_eq!(
            buf.quad_count, 6,
            "a solid 16³ cube has 6 outer faces, each merged into exactly 1 quad by greedy meshing"
        );
        assert_eq!(buf.vertices.len(), 24);
        assert_eq!(buf.indices.len(), 36);
    }

    #[test]
    fn vertex_struct_layout_is_32_bytes() {
        assert_eq!(
            std::mem::size_of::<LodVertex>(),
            32,
            "LodVertex must be exactly 32 bytes for strict GL Core Profile alignment"
        );
    }
}
