pub struct Mesh {
    pub vertices: Vec<f32>,
    pub colors: Vec<u32>,
    pub indices: Vec<u32>,
}

pub fn greedy_mesh(opaque: &[bool], argb: &[i32], gx: usize, gy: usize, gz: usize) -> Mesh {
    let dims = [gx, gy, gz];
    let cell = |x: usize, y: usize, z: usize| (y * gz + z) * gx + x;

    let mut vertices: Vec<f32> = Vec::new();
    let mut colors: Vec<u32> = Vec::new();
    let mut indices: Vec<u32> = Vec::new();

    for d in 0..3usize {
        let u = (d + 1) % 3;
        let v = (d + 2) % 3;
        let du = dims[u];
        let dv = dims[v];

        let plane = |w: isize, i: usize, j: usize| -> Option<(u32, usize)> {
            if w < 0 || (w as usize) >= dims[d] {
                return None;
            }
            let mut c = [0usize; 3];
            c[d] = w as usize;
            c[u] = i;
            c[v] = j;
            let li = cell(c[0], c[1], c[2]);
            if opaque[li] {
                Some((argb[li] as u32, li))
            } else {
                None
            }
        };

        let mut mask: Vec<Option<(u32, bool)>> = vec![None; du * dv];

        for w in -1..(dims[d] as isize) {
            for j in 0..dv {
                for i in 0..du {
                    let a = plane(w, i, j);
                    let b = plane(w + 1, i, j);
                    mask[i + j * du] = match (a, b) {
                        (Some((ca, _)), None) => Some((ca, false)),
                        (None, Some((cb, _))) => Some((cb, true)),
                        _ => None,
                    };
                }
            }

            let pos_d = (w + 1) as f32;
            let mut j = 0usize;
            while j < dv {
                let mut i = 0usize;
                while i < du {
                    let n = i + j * du;
                    if let Some(current) = mask[n] {
                        let mut width = 1usize;
                        while i + width < du && mask[n + width] == Some(current) {
                            width += 1;
                        }
                        let mut height = 1usize;
                        'grow: while j + height < dv {
                            for k in 0..width {
                                if mask[n + k + height * du] != Some(current) {
                                    break 'grow;
                                }
                            }
                            height += 1;
                        }

                        let (color, back) = current;

                        let mut base = [0f32; 3];
                        base[d] = pos_d;
                        base[u] = i as f32;
                        base[v] = j as f32;

                        let mut du_vec = [0f32; 3];
                        du_vec[u] = width as f32;
                        let mut dv_vec = [0f32; 3];
                        dv_vec[v] = height as f32;

                        let mut normal = [0f32; 3];
                        normal[d] = if back { -1.0 } else { 1.0 };

                        let corners = [
                            base,
                            [base[0] + du_vec[0], base[1] + du_vec[1], base[2] + du_vec[2]],
                            [
                                base[0] + du_vec[0] + dv_vec[0],
                                base[1] + du_vec[1] + dv_vec[1],
                                base[2] + du_vec[2] + dv_vec[2],
                            ],
                            [base[0] + dv_vec[0], base[1] + dv_vec[1], base[2] + dv_vec[2]],
                        ];

                        let vbase = (vertices.len() / 6) as u32;
                        for corner in corners.iter() {
                            vertices.extend_from_slice(corner);
                            vertices.extend_from_slice(&normal);
                            colors.push(color);
                        }

                        if back {
                            indices.extend_from_slice(&[
                                vbase,
                                vbase + 2,
                                vbase + 1,
                                vbase,
                                vbase + 3,
                                vbase + 2,
                            ]);
                        } else {
                            indices.extend_from_slice(&[
                                vbase,
                                vbase + 1,
                                vbase + 2,
                                vbase,
                                vbase + 2,
                                vbase + 3,
                            ]);
                        }

                        for l in 0..height {
                            for k in 0..width {
                                mask[n + k + l * du] = None;
                            }
                        }

                        i += width;
                    } else {
                        i += 1;
                    }
                }
                j += 1;
            }
        }
    }

    Mesh {
        vertices,
        colors,
        indices,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn idx(x:usize,y:usize,z:usize,gx:usize,gz:usize)->usize { (y*gz+z)*gx+x }

    #[test]
    fn all_air_no_quads() {
        let m = greedy_mesh(&vec![false;6*6*6], &vec![0;6*6*6], 6,6,6);
        assert!(m.indices.is_empty());
    }

    #[test]
    fn full_solid_only_outer_faces() {
        // Whole 4^3 grid solid: interior faces are hidden; only the 6 outer sides remain, and each
        // side is one merged rectangle -> 6 quads = 12 tris = 36 indices.
        let n = 4*4*4;
        let m = greedy_mesh(&vec![true;n], &vec![0xFF808080u32 as i32;n], 4,4,4);
        assert_eq!(m.indices.len(), 36, "6 merged outer faces -> 12 tris");
    }

    #[test]
    fn single_cell_is_a_closed_cube() {
        let mut o = vec![false;3*3*3]; o[idx(1,1,1,3,3)] = true;
        let mut c = vec![0i32;3*3*3]; c[idx(1,1,1,3,3)] = 0xFF3366CCu32 as i32;
        let m = greedy_mesh(&o,&c,3,3,3);
        assert_eq!(m.indices.len(), 36, "6 faces of one cube -> 12 tris");
        assert_eq!(m.vertices.len()/6, m.colors.len(), "one colour per vertex, 6 floats/vertex");
        assert!(m.indices.iter().all(|&i| (i as usize) < m.colors.len()), "indices in range");
    }
}
