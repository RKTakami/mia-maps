//! The JNI surface.
//!
//! Deliberately thin: every non-trivial decision lives in [`crate::section`], [`crate::lod`] and
//! [`crate::store`], where it can be unit-tested without a JVM. This layer converts types, guards
//! the FFI boundary, and nothing else.
//!
//! Java side is `com.mia.aperture.lod.LodNative`. Conventions mirror MIA Maps' existing
//! `map-native`: an opaque `long` handle from an open call, caller-provided arrays on hot paths, and
//! `jni` 0.21.

use crate::cascade::CascadePlanner;
use crate::mesher::{CpuMesher, Mesher};
use crate::section::{Section, BIOME_CELLS, CELLS};
use crate::store::Store;
use jni::objects::{JClass, JIntArray, JString};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

/// Value format version. Bump when the on-disk encoding changes so the mod can refuse a store it
/// cannot read rather than misinterpreting it.
pub const FORMAT_VERSION: jint = 3;

/// Run a body, turning any panic into a value rather than unwinding across the FFI boundary.
///
/// A panic crossing into the JVM is undefined behaviour, and it would surface as a JVM crash with no
/// usable stack — so every entry point goes through here, however unlikely a panic looks.
fn guard<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    // AssertUnwindSafe: on a panic we discard everything and return `fallback`, so no
    // possibly-inconsistent state is observed afterwards. JNIEnv is not UnwindSafe by default.
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => {
            eprintln!("[mia-lods] panic caught at the JNI boundary");
            fallback
        }
    }
}

/// Borrow a handle produced by `nOpen`. Returns `None` for 0, which is what `nOpen` returns on
/// failure, so a caller that ignored the failure gets a defined no-op rather than a crash.
unsafe fn store<'a>(handle: jlong) -> Option<&'a Store> {
    if handle == 0 {
        None
    } else {
        Some(&*(handle as *const Store))
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    FORMAT_VERSION
}

/// Open (or create) a store. Returns a handle, or 0 on failure.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nOpen(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    guard(0, || {
        let Ok(p) = env.get_string(&path) else {
            return 0;
        };
        let p: String = p.into();
        match Store::open(&p) {
            Ok(s) => Box::into_raw(Box::new(s)) as jlong,
            Err(e) => {
                eprintln!("[mia-lods] open failed for {p}: {e}");
                0
            }
        }
    })
}

/// Close a store. Safe to call with 0. **Must not be called while another thread is inside any
/// other call on the same handle.**
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guard((), || {
        if handle != 0 {
            drop(unsafe { Box::from_raw(handle as *mut Store) });
        }
    })
}

/// Read a section into caller-provided arrays.
///
/// Returns true when the section exists. **False means "never seen" — an all-air section that HAS
/// been seen returns true with zeroed arrays**, and callers need that distinction: one means keep
/// looking, the other means there is genuinely nothing there.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nGet(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    level: jint,
    x: jint,
    y: jint,
    z: jint,
    ids_out: JIntArray,
    biomes_out: JIntArray,
) -> jboolean {
    guard(JNI_FALSE, || {
        let Some(st) = (unsafe { store(handle) }) else {
            return JNI_FALSE;
        };
        let section = match st.get(level as u8, x, y, z) {
            Ok(Some(s)) => s,
            Ok(None) => return JNI_FALSE,
            Err(e) => {
                eprintln!("[mia-lods] read failed at {level}/{x},{y},{z}: {e}");
                return JNI_FALSE;
            }
        };
        let mut ids = vec![0u32; CELLS];
        if !section.to_ids(&mut ids) {
            return JNI_FALSE;
        }
        let out: Vec<i32> = ids.into_iter().map(|v| v as i32).collect();
        if env.set_int_array_region(&ids_out, 0, &out).is_err() {
            return JNI_FALSE;
        }
        // Zeroed when the section predates biome recording, so the caller sees "unknown" rather
        // than a stale array from a previous read.
        let b = section.biomes();
        let bout: Vec<i32> = if b.len() == BIOME_CELLS {
            b.iter().map(|v| *v as i32).collect()
        } else {
            vec![0i32; BIOME_CELLS]
        };
        let _ = env.set_int_array_region(&biomes_out, 0, &bout);
        JNI_TRUE
    })
}

/// Record a level-0 section and mark its ancestors for rebuild.
///
/// **Does not fold the pyramid** — call `nFlush` periodically while indexing and once when done.
/// Folding per section would read eight children at every level on every write, and chunks arrive
/// tens per second while exploring.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nIndex(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    ids_in: JIntArray,
    biomes_in: JIntArray,
) -> jboolean {
    guard(JNI_FALSE, || {
        let Some(st) = (unsafe { store(handle) }) else {
            return JNI_FALSE;
        };
        let mut raw = vec![0i32; CELLS];
        if env.get_int_array_region(&ids_in, 0, &mut raw).is_err() {
            return JNI_FALSE;
        }
        let ids: Vec<u32> = raw.into_iter().map(|v| v as u32).collect();
        let Some(mut section) = Section::from_ids(&ids) else {
            return JNI_FALSE;
        };
        // Biomes are optional: a caller that cannot supply them stores terrain that reads untinted
        // rather than failing, which is what lets indexing predate this.
        let mut braw = vec![0i32; BIOME_CELLS];
        if env.get_int_array_region(&biomes_in, 0, &mut braw).is_ok() {
            let biomes: Vec<u32> = braw.into_iter().map(|v| v as u32).collect();
            section.set_biomes(&biomes);
        }
        match st.index(x, y, z, &section) {
            Ok(()) => JNI_TRUE,
            Err(e) => {
                eprintln!("[mia-lods] index failed at {x},{y},{z}: {e}");
                JNI_FALSE
            }
        }
    })
}

/// Fold every parent marked dirty. Returns how many were rebuilt, or -1 on failure.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nFlush(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guard(-1, || {
        let Some(st) = (unsafe { store(handle) }) else {
            return -1;
        };
        match st.flush() {
            Ok(n) => n as jint,
            Err(e) => {
                eprintln!("[mia-lods] flush failed: {e}");
                -1
            }
        }
    })
}

/// Parents awaiting a rebuild, or -1 on failure. Diagnostics.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nDirtyCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    guard(-1, || {
        let Some(st) = (unsafe { store(handle) }) else {
            return -1;
        };
        st.dirty_count().map(|n| n as jlong).unwrap_or(-1)
    })
}

/// Sections stored across all levels, or -1 on failure. Diagnostics.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nLen(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    guard(-1, || {
        let Some(st) = (unsafe { store(handle) }) else {
            return -1;
        };
        st.len().map(|n| n as jlong).unwrap_or(-1)
    })
}

/// Get or assign the stable id for a block state, identified by its canonical string.
///
/// **Call this before indexing anything.** Stored cells hold these ids, not the game's runtime ids,
/// which move with the version and the mod set. `flags` describe the block type once — bit 0 OPAQUE,
/// 1 WATER, 2 FOLIAGE. Re-interning an existing key updates its flags, so a consumer that refines
/// its classification does not have to re-index the world.
///
/// Returns the id, or 0 on failure — and 0 is also air, which is never interned, so a caller that
/// ignores the failure writes air rather than mislabelled terrain.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nInternBlock(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JString,
    flags: jint,
) -> jint {
    guard(0, || {
        let Some(st) = (unsafe { store(handle) }) else {
            return 0;
        };
        let Ok(k) = env.get_string(&key) else {
            return 0;
        };
        let k: String = k.into();
        match st.intern_block(&k, flags as u16) {
            Ok(id) => id as jint,
            Err(e) => {
                eprintln!("[mia-lods] intern failed for {k}: {e}");
                0
            }
        }
    })
}

/// The canonical block-state string for an id, or null if it was never interned.
///
/// A consumer builds its own id mapping on load by walking its ids through this — which is how
/// terrain indexed under one game version stays readable under another.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nBlockKey<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    handle: jlong,
    id: jint,
) -> JString<'a> {
    let null = JString::from(jni::objects::JObject::null());
    guard(null, || {
        let Some(st) = (unsafe { store(handle) }) else {
            return JString::from(jni::objects::JObject::null());
        };
        match st.block(id as u32) {
            Ok(Some((_flags, key))) => env
                .new_string(key)
                .unwrap_or_else(|_| JString::from(jni::objects::JObject::null())),
            _ => JString::from(jni::objects::JObject::null()),
        }
    })
}

/// Sections skipped as unchanged since the store was opened, or -1 on failure.
///
/// Diagnostics, and the way to tell whether deduplication is earning its place: a high number
/// against the indexed count is exactly the redundant-write problem it exists to remove.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nSkipped(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    guard(-1, || {
        let Some(st) = (unsafe { store(handle) }) else {
            return -1;
        };
        st.skipped() as jlong
    })
}

/// Meshes a stored section using the CPU greedy mesher.
///
/// Writes packed 32-byte `LodVertex` structs (8 x `i32` per vertex) into `vertex_out`, and u32
/// triangle indices into `index_out`. Returns `(vertex_count << 32) | index_count` as a `jlong`,
/// or -1 on failure.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nMeshSection(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    level: jint,
    x: jint,
    y: jint,
    z: jint,
    vertex_out: JIntArray,
    index_out: JIntArray,
) -> jlong {
    guard(-1, || {
        let Some(st) = (unsafe { store(handle) }) else {
            return -1;
        };
        let section = match st.get(level as u8, x, y, z) {
            Ok(Some(s)) => s,
            _ => return 0,
        };
        if section.is_air() {
            return 0;
        }
        let mesher = CpuMesher::new();
        let color_for_id = |id: u32| -> [u8; 4] {
            let key = match st.block(id) {
                Ok(Some((_flags, key))) => key,
                _ => return [120, 115, 110, 255],
            };
            if key.contains("grass") || key.contains("fern") {
                [91, 140, 68, 255]
            } else if key.contains("leaves") || key.contains("foliage") || key.contains("vine") {
                [68, 120, 48, 255]
            } else if key.contains("water") {
                [63, 118, 228, 200]
            } else if key.contains("dirt") || key.contains("podzol") || key.contains("farmland") {
                [134, 96, 67, 255]
            } else if key.contains("sand") || key.contains("sandstone") {
                [219, 207, 163, 255]
            } else if key.contains("snow") || key.contains("calcite") || key.contains("quartz") {
                [240, 248, 255, 255]
            } else if key.contains("ice") || key.contains("frost") {
                [160, 210, 255, 220]
            } else if key.contains("deepslate") || key.contains("obsidian") || key.contains("basalt") || key.contains("coal") {
                [78, 78, 80, 255]
            } else if key.contains("gravel") || key.contains("tuff") || key.contains("andesite") {
                [118, 116, 113, 255]
            } else if key.contains("stone") || key.contains("cobble") || key.contains("diorite") || key.contains("granite") {
                [128, 128, 128, 255]
            } else if key.contains("moss") {
                [89, 109, 45, 255]
            } else if key.contains("mud") || key.contains("dripstone") {
                [85, 65, 55, 255]
            } else if key.contains("clay") {
                [160, 166, 179, 255]
            } else if key.contains("lava") || key.contains("magma") || key.contains("fire") {
                [230, 80, 15, 255]
            } else if key.contains("nether") || key.contains("crimson") {
                [114, 50, 50, 255]
            } else if key.contains("end_stone") || key.contains("purpur") {
                [222, 224, 165, 255]
            } else if key.contains("wood") || key.contains("log") || key.contains("planks") || key.contains("timber") {
                [145, 110, 70, 255]
            } else {
                [120, 115, 110, 255]
            }
        };
        // Default drawable check: non-air blocks greater than 0
        let buf = mesher.mesh(&section, &|id| id > 0, &color_for_id);
        if buf.is_empty() {
            return 0;
        }
        let v_len = buf.vertices.len();
        let i_len = buf.indices.len();
        // Safe reinterpret of LodVertex slice to &[i32], since LodVertex is #[repr(C)] and 32 bytes (8 * i32)
        let v_i32s: &[i32] = unsafe {
            std::slice::from_raw_parts(buf.vertices.as_ptr() as *const i32, v_len * 8)
        };
        let i_i32s: &[i32] = unsafe {
            std::slice::from_raw_parts(buf.indices.as_ptr() as *const i32, i_len)
        };
        if env.set_int_array_region(&vertex_out, 0, v_i32s).is_err() {
            return -1;
        }
        if env.set_int_array_region(&index_out, 0, i_i32s).is_err() {
            return -1;
        }
        ((v_len as jlong) << 32) | (i_len as jlong)
    })
}

/// Plans the visible distance cascade for the given camera position and view distance.
///
/// Writes packed `(level, x, y, z)` tile keys into `tiles_out` and returns the number of
/// visible tiles, or -1 on failure.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_lod_LodNative_nPlanCascade(
    env: JNIEnv,
    _class: JClass,
    cam_x: f32,
    cam_y: f32,
    cam_z: f32,
    view_dist: f32,
    min_y: jint,
    max_y: jint,
    tiles_out: JIntArray,
) -> jint {
    guard(-1, || {
        let planner = CascadePlanner::default();
        let tiles = planner.plan(cam_x, cam_y, cam_z, view_dist, min_y, max_y);
        let max_tiles = match env.get_array_length(&tiles_out) {
            Ok(len) => (len as usize) / 4,
            Err(_) => return -1,
        };
        let count = tiles.len().min(max_tiles);
        let mut packed = Vec::with_capacity(count * 4);
        for tile in tiles.iter().take(count) {
            packed.push(tile.level as i32);
            packed.push(tile.section_x);
            packed.push(tile.section_y);
            packed.push(tile.section_z);
        }
        if env.set_int_array_region(&tiles_out, 0, &packed).is_err() {
            return -1;
        }
        count as jint
    })
}

