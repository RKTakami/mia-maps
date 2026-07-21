use crate::gl;
use crate::shader;
use std::ffi::CString;
use std::sync::Mutex;

// A finished mesh produced on the WORKER thread (pure CPU, no GL), handed to the render thread.
pub struct PendingMesh {
    pub verts: Vec<f32>,
    pub colors: Vec<u32>,
    pub indices: Vec<u32>,
    pub cell: f32,
    pub origin: [f32; 3],
}

// GL objects — created and used on the RENDER thread only, behind the `gl` mutex.
struct GlState {
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

// Shared between the worker (stage) and render (render) threads. Both mutexes make Ctx Sync, so the
// raw handle can be dereferenced as &Ctx from either thread. GL is only ever touched under `gl` on
// the render thread; the worker only touches `pending`.
pub struct Ctx {
    pending: Mutex<Option<PendingMesh>>,
    gl: Mutex<GlState>,
}

pub fn create() -> Box<Ctx> {
    let mut fbo = 0u32;
    unsafe {
        gl::GenFramebuffers(1, &mut fbo);
    }
    Box::new(Ctx {
        pending: Mutex::new(None),
        gl: Mutex::new(GlState {
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
        }),
    })
}

pub fn destroy(ctx: Box<Ctx>) {
    let g = ctx.gl.lock().unwrap();
    unsafe {
        gl::DeleteFramebuffers(1, &g.fbo);
        if g.vao != 0 {
            gl::DeleteVertexArrays(1, &g.vao);
        }
        if g.vbo != 0 {
            gl::DeleteBuffers(1, &g.vbo);
        }
        if g.cbo != 0 {
            gl::DeleteBuffers(1, &g.cbo);
        }
        if g.ibo != 0 {
            gl::DeleteBuffers(1, &g.ibo);
        }
        if g.depth_rbo != 0 {
            gl::DeleteRenderbuffers(1, &g.depth_rbo);
        }
        if g.program != 0 {
            gl::DeleteProgram(g.program);
        }
    }
}

// WORKER thread: publish the latest finished mesh, dropping any older un-uploaded one. No GL.
pub fn stage(ctx: &Ctx, mesh: PendingMesh) {
    *ctx.pending.lock().unwrap() = Some(mesh);
}

// RENDER thread: adopt a staged mesh (GL upload) if one is waiting, then draw.
pub fn render(ctx: &Ctx, mvp: &[f32; 16], tex_id: u32, w: i32, h: i32) {
    let staged = ctx.pending.lock().unwrap().take();
    let mut g = ctx.gl.lock().unwrap();
    if let Some(mesh) = staged {
        unsafe {
            upload(&mut g, &mesh);
        }
    }
    if g.program == 0 || g.vao == 0 || g.index_count == 0 {
        return;
    }
    unsafe {
        draw(&mut g, mvp, tex_id, w, h);
    }
}

// RENDER thread only (caller holds the gl lock). The one cheap GL step left on the render thread.
unsafe fn upload(g: &mut GlState, mesh: &PendingMesh) {
    if g.program == 0 {
        g.program = shader::compile_program(shader::MAP_VSH, shader::MAP_FSH);
    }
    if g.vao == 0 {
        gl::GenVertexArrays(1, &mut g.vao);
        gl::GenBuffers(1, &mut g.vbo);
        gl::GenBuffers(1, &mut g.cbo);
        gl::GenBuffers(1, &mut g.ibo);
    }

    gl::BindVertexArray(g.vao);

    gl::BindBuffer(gl::ARRAY_BUFFER, g.vbo);
    gl::BufferData(
        gl::ARRAY_BUFFER,
        (mesh.verts.len() * std::mem::size_of::<f32>()) as isize,
        mesh.verts.as_ptr() as *const std::ffi::c_void,
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

    gl::BindBuffer(gl::ARRAY_BUFFER, g.cbo);
    gl::BufferData(
        gl::ARRAY_BUFFER,
        (mesh.colors.len() * std::mem::size_of::<u32>()) as isize,
        mesh.colors.as_ptr() as *const std::ffi::c_void,
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

    gl::BindBuffer(gl::ELEMENT_ARRAY_BUFFER, g.ibo);
    gl::BufferData(
        gl::ELEMENT_ARRAY_BUFFER,
        (mesh.indices.len() * std::mem::size_of::<u32>()) as isize,
        mesh.indices.as_ptr() as *const std::ffi::c_void,
        gl::STATIC_DRAW,
    );

    gl::BindVertexArray(0);
    gl::BindBuffer(gl::ARRAY_BUFFER, 0);
    gl::BindBuffer(gl::ELEMENT_ARRAY_BUFFER, 0);

    g.index_count = mesh.indices.len() as i32;
    g.cell = mesh.cell;
    g.origin = mesh.origin;
}

unsafe fn draw(g: &mut GlState, mvp: &[f32; 16], tex_id: u32, w: i32, h: i32) {
    let mut prev = 0i32;
    gl::GetIntegerv(gl::DRAW_FRAMEBUFFER_BINDING, &mut prev);

    if g.depth_rbo == 0 || g.depth_w != w || g.depth_h != h {
        if g.depth_rbo == 0 {
            gl::GenRenderbuffers(1, &mut g.depth_rbo);
        }
        gl::BindRenderbuffer(gl::RENDERBUFFER, g.depth_rbo);
        gl::RenderbufferStorage(gl::RENDERBUFFER, gl::DEPTH_COMPONENT24, w, h);
        gl::BindRenderbuffer(gl::RENDERBUFFER, 0);
        g.depth_w = w;
        g.depth_h = h;
    }

    gl::BindFramebuffer(gl::FRAMEBUFFER, g.fbo);
    gl::FramebufferTexture2D(gl::FRAMEBUFFER, gl::COLOR_ATTACHMENT0, gl::TEXTURE_2D, tex_id, 0);
    gl::FramebufferRenderbuffer(gl::FRAMEBUFFER, gl::DEPTH_ATTACHMENT, gl::RENDERBUFFER, g.depth_rbo);

    gl::ColorMask(gl::TRUE, gl::TRUE, gl::TRUE, gl::TRUE);
    gl::Viewport(0, 0, w, h);
    gl::Enable(gl::DEPTH_TEST);
    // Culling OFF for now: the greedy-mesh winding is reversed relative to this MVP, so BACK-cull
    // blanks the view. The depth test keeps a closed solid surface correct; re-enabling cull (reverse
    // winding / FrontFace) is a perf follow-up.
    gl::Disable(gl::CULL_FACE);
    // Querying completeness is load-bearing on this AMD driver: without it the mesh does not rasterize
    // into the freshly-attached FBO (the texture came back blank). Clear OPAQUE black.
    let _status = gl::CheckFramebufferStatus(gl::FRAMEBUFFER);
    gl::ClearColor(0.0, 0.0, 0.0, 1.0);
    gl::Clear(gl::COLOR_BUFFER_BIT | gl::DEPTH_BUFFER_BIT);

    gl::UseProgram(g.program);

    let name_mvp = CString::new("uMVP").unwrap();
    let name_cell = CString::new("uCell").unwrap();
    let name_origin = CString::new("uOrigin").unwrap();
    let loc_mvp = gl::GetUniformLocation(g.program, name_mvp.as_ptr());
    let loc_cell = gl::GetUniformLocation(g.program, name_cell.as_ptr());
    let loc_origin = gl::GetUniformLocation(g.program, name_origin.as_ptr());

    gl::UniformMatrix4fv(loc_mvp, 1, gl::FALSE, mvp.as_ptr());
    gl::Uniform1f(loc_cell, g.cell);
    gl::Uniform3f(loc_origin, g.origin[0], g.origin[1], g.origin[2]);

    gl::BindVertexArray(g.vao);
    gl::DrawElements(gl::TRIANGLES, g.index_count, gl::UNSIGNED_INT, std::ptr::null());
    gl::BindVertexArray(0);

    gl::UseProgram(0);
    gl::BindFramebuffer(gl::FRAMEBUFFER, prev as u32);
    gl::Disable(gl::DEPTH_TEST);
}
