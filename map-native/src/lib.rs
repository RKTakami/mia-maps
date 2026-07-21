pub mod gl;
pub mod mesher;

use jni::objects::JClass;
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
