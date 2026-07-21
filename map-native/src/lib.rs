pub mod gl;
pub mod mesher;
pub mod renderer;

use jni::objects::JClass;
use jni::sys::{jint, jlong};
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

#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_map_MapNative_nClear<'local>(
    _env: JNIEnv<'local>, _class: JClass<'local>, handle: jlong, tex_id: jint, w: jint, h: jint,
) {
    if handle == 0 {
        return;
    }
    let ctx = unsafe { &*(handle as *const renderer::Ctx) };
    renderer::clear_into(ctx, tex_id as u32, w, h, 0.2, 0.6, 1.0);
}
