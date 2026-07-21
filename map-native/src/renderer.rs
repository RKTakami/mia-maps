use crate::gl;
use crate::shader;
use std::ffi::CString;

pub struct Ctx {
    fbo: u32,
    vao: u32,
    vbo: u32,
    cbo: u32,
    ibo: u32,
    program: u32,
    index_count: i32,
    depth_rbo: u32,
    depth_w: i32,
    depth_h: i32,
    cell: f32,
    origin: [f32; 3],
}

pub fn create() -> Box<Ctx> {
    let mut fbo = 0u32;
    unsafe {
        gl::GenFramebuffers(1, &mut fbo);
    }
    Box::new(Ctx {
        fbo,
        vao: 0,
        vbo: 0,
        cbo: 0,
        ibo: 0,
        program: 0,
        index_count: 0,
        depth_rbo: 0,
        depth_w: 0,
        depth_h: 0,
        cell: 1.0,
        origin: [0.0, 0.0, 0.0],
    })
}

pub fn destroy(ctx: Box<Ctx>) {
    unsafe {
        gl::DeleteFramebuffers(1, &ctx.fbo);
        if ctx.vao != 0 {
            gl::DeleteVertexArrays(1, &ctx.vao);
        }
        if ctx.vbo != 0 {
            gl::DeleteBuffers(1, &ctx.vbo);
        }
        if ctx.cbo != 0 {
            gl::DeleteBuffers(1, &ctx.cbo);
        }
        if ctx.ibo != 0 {
            gl::DeleteBuffers(1, &ctx.ibo);
        }
        if ctx.depth_rbo != 0 {
            gl::DeleteRenderbuffers(1, &ctx.depth_rbo);
        }
        if ctx.program != 0 {
            gl::DeleteProgram(ctx.program);
        }
    }
}

pub fn clear_into(ctx: &Ctx, tex_id: u32, w: i32, h: i32, r: f32, g: f32, b: f32) {
    unsafe {
        let mut prev = 0i32;
        gl::GetIntegerv(gl::DRAW_FRAMEBUFFER_BINDING, &mut prev);
        gl::BindFramebuffer(gl::FRAMEBUFFER, ctx.fbo);
        gl::FramebufferTexture2D(gl::FRAMEBUFFER, gl::COLOR_ATTACHMENT0, gl::TEXTURE_2D, tex_id, 0);
        gl::ColorMask(gl::TRUE, gl::TRUE, gl::TRUE, gl::TRUE);
        gl::Viewport(0, 0, w, h);
        gl::ClearColor(r, g, b, 1.0);
        gl::Clear(gl::COLOR_BUFFER_BIT);
        gl::BindFramebuffer(gl::FRAMEBUFFER, prev as u32);
    }
}

pub fn upload(
    ctx: &mut Ctx,
    verts: &[f32],
    colors: &[u32],
    indices: &[u32],
    cell: f32,
    origin: [f32; 3],
) {
    unsafe {
        if ctx.program == 0 {
            ctx.program = shader::compile_program(shader::MAP_VSH, shader::MAP_FSH);
        }
        if ctx.vao == 0 {
            gl::GenVertexArrays(1, &mut ctx.vao);
            gl::GenBuffers(1, &mut ctx.vbo);
            gl::GenBuffers(1, &mut ctx.cbo);
            gl::GenBuffers(1, &mut ctx.ibo);
        }

        gl::BindVertexArray(ctx.vao);

        gl::BindBuffer(gl::ARRAY_BUFFER, ctx.vbo);
        gl::BufferData(
            gl::ARRAY_BUFFER,
            (verts.len() * std::mem::size_of::<f32>()) as isize,
            verts.as_ptr() as *const std::ffi::c_void,
            gl::STATIC_DRAW,
        );
        let stride = 6 * std::mem::size_of::<f32>() as i32;
        gl::EnableVertexAttribArray(0);
        gl::VertexAttribPointer(0, 3, gl::FLOAT, gl::FALSE, stride, std::ptr::null());
        gl::EnableVertexAttribArray(1);
        gl::VertexAttribPointer(
            1,
            3,
            gl::FLOAT,
            gl::FALSE,
            stride,
            (3 * std::mem::size_of::<f32>()) as *const std::ffi::c_void,
        );

        gl::BindBuffer(gl::ARRAY_BUFFER, ctx.cbo);
        gl::BufferData(
            gl::ARRAY_BUFFER,
            (colors.len() * std::mem::size_of::<u32>()) as isize,
            colors.as_ptr() as *const std::ffi::c_void,
            gl::STATIC_DRAW,
        );
        gl::EnableVertexAttribArray(2);
        gl::VertexAttribPointer(
            2,
            4,
            gl::UNSIGNED_BYTE,
            gl::TRUE,
            4 * std::mem::size_of::<u8>() as i32,
            std::ptr::null(),
        );

        gl::BindBuffer(gl::ELEMENT_ARRAY_BUFFER, ctx.ibo);
        gl::BufferData(
            gl::ELEMENT_ARRAY_BUFFER,
            (indices.len() * std::mem::size_of::<u32>()) as isize,
            indices.as_ptr() as *const std::ffi::c_void,
            gl::STATIC_DRAW,
        );

        gl::BindVertexArray(0);
        gl::BindBuffer(gl::ARRAY_BUFFER, 0);
        gl::BindBuffer(gl::ELEMENT_ARRAY_BUFFER, 0);
    }
    ctx.index_count = indices.len() as i32;
    ctx.cell = cell;
    ctx.origin = origin;
}

pub fn render(ctx: &mut Ctx, mvp: &[f32; 16], tex_id: u32, w: i32, h: i32) {
    if ctx.program == 0 || ctx.vao == 0 || ctx.index_count == 0 {
        return;
    }
    unsafe {
        let mut prev = 0i32;
        gl::GetIntegerv(gl::DRAW_FRAMEBUFFER_BINDING, &mut prev);

        if ctx.depth_rbo == 0 || ctx.depth_w != w || ctx.depth_h != h {
            if ctx.depth_rbo == 0 {
                gl::GenRenderbuffers(1, &mut ctx.depth_rbo);
            }
            gl::BindRenderbuffer(gl::RENDERBUFFER, ctx.depth_rbo);
            gl::RenderbufferStorage(gl::RENDERBUFFER, gl::DEPTH_COMPONENT24, w, h);
            gl::BindRenderbuffer(gl::RENDERBUFFER, 0);
            ctx.depth_w = w;
            ctx.depth_h = h;
        }

        gl::BindFramebuffer(gl::FRAMEBUFFER, ctx.fbo);
        gl::FramebufferTexture2D(gl::FRAMEBUFFER, gl::COLOR_ATTACHMENT0, gl::TEXTURE_2D, tex_id, 0);
        gl::FramebufferRenderbuffer(
            gl::FRAMEBUFFER,
            gl::DEPTH_ATTACHMENT,
            gl::RENDERBUFFER,
            ctx.depth_rbo,
        );

        gl::ColorMask(gl::TRUE, gl::TRUE, gl::TRUE, gl::TRUE);
        gl::Viewport(0, 0, w, h);
        gl::Enable(gl::DEPTH_TEST);
        // Culling OFF for now: the greedy-mesh winding is reversed relative to this MVP, so BACK-cull
        // blanks the view. With the depth test on, a closed solid surface still renders correctly
        // (front faces win); re-enabling cull (reverse winding / FrontFace) is a perf follow-up.
        gl::Disable(gl::CULL_FACE);
        // Querying completeness is load-bearing on this AMD driver: without it the mesh does not
        // rasterize into the freshly-attached FBO (the texture came back blank). Clear OPAQUE black:
        // a transparent (alpha 0) clear also left the view blank here, so the background stays opaque.
        let _status = gl::CheckFramebufferStatus(gl::FRAMEBUFFER);
        gl::ClearColor(0.0, 0.0, 0.0, 1.0);
        gl::Clear(gl::COLOR_BUFFER_BIT | gl::DEPTH_BUFFER_BIT);

        gl::UseProgram(ctx.program);

        let name_mvp = CString::new("uMVP").unwrap();
        let name_cell = CString::new("uCell").unwrap();
        let name_origin = CString::new("uOrigin").unwrap();
        let loc_mvp = gl::GetUniformLocation(ctx.program, name_mvp.as_ptr());
        let loc_cell = gl::GetUniformLocation(ctx.program, name_cell.as_ptr());
        let loc_origin = gl::GetUniformLocation(ctx.program, name_origin.as_ptr());

        gl::UniformMatrix4fv(loc_mvp, 1, gl::FALSE, mvp.as_ptr());
        gl::Uniform1f(loc_cell, ctx.cell);
        gl::Uniform3f(loc_origin, ctx.origin[0], ctx.origin[1], ctx.origin[2]);

        gl::BindVertexArray(ctx.vao);
        gl::DrawElements(
            gl::TRIANGLES,
            ctx.index_count,
            gl::UNSIGNED_INT,
            std::ptr::null(),
        );
        gl::BindVertexArray(0);

        gl::UseProgram(0);
        gl::BindFramebuffer(gl::FRAMEBUFFER, prev as u32);
        gl::Disable(gl::CULL_FACE);
        gl::Disable(gl::DEPTH_TEST);
    }
}
