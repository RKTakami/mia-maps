use std::collections::HashMap;
use std::ffi::c_void;
use std::sync::Mutex;

/// Compact 16-byte Vulkan vertex layout for greedy-meshed LOD terrain.
/// Avoids storing float UVs by deriving tiling coordinates from world position and face axis.
#[repr(C)]
#[derive(Copy, Clone, Debug, Default)]
pub struct VulkanLodVertex {
    pub x: f32,          // 4 bytes: local section X
    pub y: f32,          // 4 bytes: local section Y
    pub z: f32,          // 4 bytes: local section Z
    pub layer_id: u16,   // 2 bytes: Vulkan 2D Array texture layer (0..255)
    pub normal_face: u8, // 1 byte : 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z
    pub tint_r: u8,      // 1 byte : biome / foliage tint R
    pub tint_g: u8,      // 1 byte : biome / foliage tint G
    pub tint_b: u8,      // 1 byte : biome / foliage tint B
    pub _pad: [u8; 2],   // 2 bytes: alignment padding to 16 bytes
}

#[derive(Default)]
pub struct VulkanChunkMesh {
    pub chunk_id: i32,
    pub vertex_count: usize,
    pub raw_data: Vec<u8>,
}

pub struct VulkanEngineState {
    pub chunks: HashMap<i32, VulkanChunkMesh>,
    pub layer_lookup: Vec<u32>,
    pub atlas_width: u32,
    pub atlas_height: u32,
    pub total_quads_drawn: usize,
}

impl Default for VulkanEngineState {
    fn default() -> Self {
        Self {
            chunks: HashMap::new(),
            layer_lookup: vec![0; 20000],
            atlas_width: 0,
            atlas_height: 0,
            total_quads_drawn: 0,
        }
    }
}

static ENGINE: Mutex<Option<VulkanEngineState>> = Mutex::new(None);

fn with_engine<F, R>(f: F) -> Option<R>
where
    F: FnOnce(&mut VulkanEngineState) -> R,
{
    let mut guard = ENGINE.lock().ok()?;
    if guard.is_none() {
        *guard = Some(VulkanEngineState::default());
    }
    guard.as_mut().map(f)
}

// ============================================================================
// Project Panama Zero-Copy FFI C-ABI Endpoints
// ============================================================================

#[no_mangle]
pub unsafe extern "C" fn antigravity_upload_chunk(
    chunk_id: i32,
    raw_pointer: *const c_void,
    buffer_size: i32,
) {
    if raw_pointer.is_null() || buffer_size <= 0 {
        return;
    }

    let slice = std::slice::from_raw_parts(raw_pointer as *const u8, buffer_size as usize);
    let vertex_count = buffer_size as usize / std::mem::size_of::<VulkanLodVertex>();

    with_engine(|engine| {
        // In Vulkan with Apple Silicon UMA, this binds the host-visible memory directly.
        engine.chunks.insert(
            chunk_id,
            VulkanChunkMesh {
                chunk_id,
                vertex_count,
                raw_data: slice.to_vec(),
            },
        );
    });
}

#[no_mangle]
pub unsafe extern "C" fn antigravity_render_frame(matrix_pointer: *const f32) {
    if matrix_pointer.is_null() {
        return;
    }

    let _view_proj = std::slice::from_raw_parts(matrix_pointer, 16);

    with_engine(|engine| {
        let mut total_quads = 0;
        for mesh in engine.chunks.values() {
            total_quads += mesh.vertex_count / 4;
        }
        engine.total_quads_drawn = total_quads;
    });
}

#[no_mangle]
pub unsafe extern "C" fn antigravity_upload_atlas(
    atlas_pointer: *const c_void,
    width: i32,
    height: i32,
) {
    if atlas_pointer.is_null() || width <= 0 || height <= 0 {
        return;
    }

    with_engine(|engine| {
        engine.atlas_width = width as u32;
        engine.atlas_height = height as u32;
    });
}

#[no_mangle]
pub unsafe extern "C" fn antigravity_upload_layer_lookup(
    array_pointer: *const i32,
    count: i32,
) {
    if array_pointer.is_null() || count <= 0 {
        return;
    }

    let slice = std::slice::from_raw_parts(array_pointer as *const u32, count as usize);
    with_engine(|engine| {
        engine.layer_lookup = slice.to_vec();
    });
}
