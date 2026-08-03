use lod_native::cascade::CascadePlanner;
use lod_native::mesher::{CpuMesher, LodVertex, Mesher};
use lod_native::section::{BlockId, Section, EDGE};

const STONE: BlockId = 1;
const _GLASS: BlockId = 2;
const WATER: BlockId = 3;

fn is_drawable(id: BlockId) -> bool {
    id == STONE || id == WATER
}

#[test]
fn greedy_meshing_merges_solid_section_into_six_quads() {
    let mut s = Section::air();
    for y in 0..EDGE {
        for z in 0..EDGE {
            for x in 0..EDGE {
                s.set(x, y, z, STONE);
            }
        }
    }

    let mesher = CpuMesher::new();
    let buf = mesher.mesh(&s, &is_drawable, &|_| [255, 255, 255, 255]);

    assert_eq!(
        buf.quad_count, 6,
        "greedy meshing must merge all 6 outer faces of a solid 16³ cube into 6 quads"
    );
    assert_eq!(buf.vertices.len(), 24);
    assert_eq!(buf.indices.len(), 36);
}

#[test]
fn straddling_transparent_blocks_remain_separate_quads() {
    let mut s = Section::air();
    // One stone block at (0, 0, 0), one water block next to it at (1, 0, 0)
    s.set(0, 0, 0, STONE);
    s.set(1, 0, 0, WATER);

    let mesher = CpuMesher::new();
    let buf = mesher.mesh(&s, &is_drawable, &|_| [255, 255, 255, 255]);

    // Because STONE and WATER are different block ids, they cannot be merged into a single quad.
    // STONE has 5 outer faces (1 touching WATER), WATER has 5 outer faces (1 touching STONE).
    assert_eq!(buf.quad_count, 10);
    assert_eq!(buf.vertices.len(), 40);
}

#[test]
fn vertex_layout_complies_with_apple_gl_4_1_core_profile() {
    assert_eq!(
        std::mem::size_of::<LodVertex>(),
        32,
        "LodVertex size must be exactly 32 bytes"
    );
    assert_eq!(
        std::mem::align_of::<LodVertex>(),
        4,
        "LodVertex alignment must be 4-byte aligned for GL Core Profile floating-point arrays"
    );

    // Check offsets of fields for glVertexAttribPointer / glVertexAttribIPointer
    assert_eq!(std::mem::offset_of!(LodVertex, position), 0);
    assert_eq!(std::mem::offset_of!(LodVertex, color), 12);
    assert_eq!(std::mem::offset_of!(LodVertex, uv), 16);
    assert_eq!(std::mem::offset_of!(LodVertex, light), 24);
    assert_eq!(std::mem::offset_of!(LodVertex, normal), 28);
    assert_eq!(std::mem::offset_of!(LodVertex, flags), 31);
}

#[test]
fn cascade_planner_generates_front_to_back_sorted_tiles() {
    let planner = CascadePlanner::default();
    let tiles = planner.plan(0.0, 64.0, 0.0, 1000.0, 0, 128);

    assert!(!tiles.is_empty(), "must generate visible cascade tiles");
    for win in tiles.windows(2) {
        let d0 = win[0].dist_sq_from(0.0, 64.0, 0.0);
        let d1 = win[1].dist_sq_from(0.0, 64.0, 0.0);
        assert!(
            d0 <= d1,
            "cascade tiles must be ordered front-to-back from camera for occlusion"
        );
    }
}
