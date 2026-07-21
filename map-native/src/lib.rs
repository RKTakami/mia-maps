pub mod gl;
pub mod mesher;
pub mod renderer;
pub mod shader;

use jni::objects::JClass;
use jni::sys::{jbooleanArray, jfloatArray, jint, jintArray, jlong};
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_map_MapNative_nInit<'local>(
    _env: JNIEnv<'local>, _class: JClass<'local>,
) -> jni::sys::jboolean {
    println!("[MIA map-native] initialized");
    jni::sys::JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_map_MapNative_nVersion<'local>(
    _env: JNIEnv<'local>, _class: JClass<'local>,
) -> jni::sys::jint {
    1
}

#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_map_MapNative_nInitGL<'local>(
    mut env: JNIEnv<'local>, class: JClass<'local>,
) {
    println!("[MIA map-native] loading OpenGL pointers...");
    gl::load_with(|symbol| {
        let j_symbol = env.new_string(symbol).unwrap();
        let result = env
            .call_static_method(
                &class,
                "getGlAddress",
                "(Ljava/lang/String;)J",
                &[jni::objects::JValue::Object(&j_symbol)],
            )
            .unwrap()
            .j()
            .unwrap();
        result as *const std::ffi::c_void
    });
    println!("[MIA map-native] OpenGL pointers loaded");
}

#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_map_MapNative_nCreateContext<'local>(
    _env: JNIEnv<'local>, _class: JClass<'local>,
) -> jlong {
    Box::into_raw(renderer::create()) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_map_MapNative_nDestroyContext<'local>(
    _env: JNIEnv<'local>, _class: JClass<'local>, handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let ctx = unsafe { Box::from_raw(handle as *mut renderer::Ctx) };
    renderer::destroy(ctx);
}

// WORKER thread: greedy-mesh the grid (pure CPU) and stage it for the render thread. No GL here, so
// the expensive meshing runs off the render thread and never hitches the frame.
#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_map_MapNative_nMeshGrid<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    opaque: jbooleanArray,
    argb: jintArray,
    gx: jint,
    gy: jint,
    gz: jint,
    cell: jint,
    ox: jint,
    oy: jint,
    oz: jint,
) {
    if handle == 0 {
        return;
    }
    let n = (gx as usize) * (gy as usize) * (gz as usize);

    let opaque_obj = unsafe { jni::objects::JBooleanArray::from_raw(opaque) };
    let argb_obj = unsafe { jni::objects::JIntArray::from_raw(argb) };

    let mut opaque_bytes = vec![0u8; n];
    if env
        .get_boolean_array_region(&opaque_obj, 0, &mut opaque_bytes)
        .is_err()
    {
        return;
    }
    let mut argb_vec = vec![0i32; n];
    if env.get_int_array_region(&argb_obj, 0, &mut argb_vec).is_err() {
        return;
    }

    let opaque_vec: Vec<bool> = opaque_bytes.iter().map(|&b| b != 0).collect();

    let mesh = mesher::greedy_mesh(&opaque_vec, &argb_vec, gx as usize, gy as usize, gz as usize);

    let ctx = unsafe { &*(handle as *const renderer::Ctx) };
    renderer::stage(
        ctx,
        renderer::PendingMesh {
            verts: mesh.vertices,
            colors: mesh.colors,
            indices: mesh.indices,
            cell: cell as f32,
            origin: [ox as f32, oy as f32, oz as f32],
        },
    );
}

#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_map_MapNative_nRender<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    mvp: jfloatArray,
    tex_id: jint,
    w: jint,
    h: jint,
) {
    if handle == 0 {
        return;
    }
    let mvp_obj = unsafe { jni::objects::JFloatArray::from_raw(mvp) };
    let mut mvp16 = [0f32; 16];
    if env.get_float_array_region(&mvp_obj, 0, &mut mvp16).is_err() {
        return;
    }
    let ctx = unsafe { &*(handle as *const renderer::Ctx) };
    renderer::render(ctx, &mvp16, tex_id as u32, w, h);
}
