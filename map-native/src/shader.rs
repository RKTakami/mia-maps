use crate::gl;
use std::ffi::CString;

pub const MAP_VSH: &str = include_str!("../shaders/map.vsh");
pub const MAP_FSH: &str = include_str!("../shaders/map.fsh");

unsafe fn compile_shader(kind: u32, src: &str) -> u32 {
    let shader = gl::CreateShader(kind);
    let c_str = CString::new(src.as_bytes()).unwrap();
    let ptr = c_str.as_ptr();
    gl::ShaderSource(shader, 1, &ptr, std::ptr::null());
    gl::CompileShader(shader);

    let mut status = 0i32;
    gl::GetShaderiv(shader, gl::COMPILE_STATUS, &mut status);
    if status != gl::TRUE as i32 {
        let mut log_len = 0i32;
        gl::GetShaderiv(shader, gl::INFO_LOG_LENGTH, &mut log_len);
        let mut log = vec![0u8; log_len.max(1) as usize];
        gl::GetShaderInfoLog(shader, log_len, std::ptr::null_mut(), log.as_mut_ptr() as *mut i8);
        eprintln!(
            "[MIA map-native] shader compile failed:\n{}",
            String::from_utf8_lossy(&log)
        );
    }
    shader
}

pub fn compile_program(vsrc: &str, fsrc: &str) -> u32 {
    unsafe {
        let vs = compile_shader(gl::VERTEX_SHADER, vsrc);
        let fs = compile_shader(gl::FRAGMENT_SHADER, fsrc);
        let program = gl::CreateProgram();
        gl::AttachShader(program, vs);
        gl::AttachShader(program, fs);
        gl::LinkProgram(program);

        let mut status = 0i32;
        gl::GetProgramiv(program, gl::LINK_STATUS, &mut status);
        if status != gl::TRUE as i32 {
            let mut log_len = 0i32;
            gl::GetProgramiv(program, gl::INFO_LOG_LENGTH, &mut log_len);
            let mut log = vec![0u8; log_len.max(1) as usize];
            gl::GetProgramInfoLog(program, log_len, std::ptr::null_mut(), log.as_mut_ptr() as *mut i8);
            eprintln!(
                "[MIA map-native] program link failed:\n{}",
                String::from_utf8_lossy(&log)
            );
        }

        gl::DetachShader(program, vs);
        gl::DetachShader(program, fs);
        gl::DeleteShader(vs);
        gl::DeleteShader(fs);
        program
    }
}
