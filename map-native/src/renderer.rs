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

// RAII snapshot of every GL state this renderer touches, restored on drop (including on an early
// return). Leaking GL state into Minecraft's own rendering has glitched the world before, so the
// guard — not hand-written unbinds — is what keeps us honest.
// Note on buffer bindings: ARRAY_BUFFER is global context state, so it is always restored;
// ELEMENT_ARRAY_BUFFER is *VAO* state, so rebinding the saved VAO restores it implicitly and it
// only needs an explicit restore when the saved VAO was 0.
struct GlStateGuard {
    draw_fbo: i32,
    read_fbo: i32,
    rbo: i32,
    vao: i32,
    vbo: i32,
    ibo: i32,
    prog: i32,
    viewport: [i32; 4],
    color_mask: [u8; 4],
    clear_color: [f32; 4],
    clear_depth: f64,
    cull_face: u8,
    depth_test: u8,
    depth_func: i32,
    depth_mask: u8,
    blend: u8,
}

impl GlStateGuard {
    unsafe fn new() -> Self {
        let mut s = Self {
            draw_fbo: 0, read_fbo: 0, rbo: 0, vao: 0, vbo: 0, ibo: 0, prog: 0,
            viewport: [0; 4], color_mask: [0; 4], clear_color: [0.0; 4], clear_depth: 1.0,
            cull_face: 0, depth_test: 0, depth_func: 0, depth_mask: 0, blend: 0,
        };
        gl::GetIntegerv(gl::DRAW_FRAMEBUFFER_BINDING, &mut s.draw_fbo);
        gl::GetIntegerv(gl::READ_FRAMEBUFFER_BINDING, &mut s.read_fbo);
        gl::GetIntegerv(gl::RENDERBUFFER_BINDING, &mut s.rbo);
        gl::GetIntegerv(gl::VERTEX_ARRAY_BINDING, &mut s.vao);
        gl::GetIntegerv(gl::ARRAY_BUFFER_BINDING, &mut s.vbo);
        gl::GetIntegerv(gl::ELEMENT_ARRAY_BUFFER_BINDING, &mut s.ibo);
        gl::GetIntegerv(gl::CURRENT_PROGRAM, &mut s.prog);
        gl::GetIntegerv(gl::VIEWPORT, s.viewport.as_mut_ptr());
        gl::GetBooleanv(gl::COLOR_WRITEMASK, s.color_mask.as_mut_ptr());
        gl::GetFloatv(gl::COLOR_CLEAR_VALUE, s.clear_color.as_mut_ptr());
        gl::GetDoublev(gl::DEPTH_CLEAR_VALUE, &mut s.clear_depth);
        gl::GetBooleanv(gl::CULL_FACE, &mut s.cull_face);
        gl::GetBooleanv(gl::DEPTH_TEST, &mut s.depth_test);
        gl::GetIntegerv(gl::DEPTH_FUNC, &mut s.depth_func);
        gl::GetBooleanv(gl::DEPTH_WRITEMASK, &mut s.depth_mask);
        gl::GetBooleanv(gl::BLEND, &mut s.blend);
        s
    }
}

impl Drop for GlStateGuard {
    fn drop(&mut self) {
        unsafe {
            gl::BindFramebuffer(gl::DRAW_FRAMEBUFFER, self.draw_fbo as u32);
            gl::BindFramebuffer(gl::READ_FRAMEBUFFER, self.read_fbo as u32);
            gl::BindRenderbuffer(gl::RENDERBUFFER, self.rbo as u32);
            gl::BindVertexArray(self.vao as u32);
            gl::BindBuffer(gl::ARRAY_BUFFER, self.vbo as u32);
            if self.vao == 0 {
                gl::BindBuffer(gl::ELEMENT_ARRAY_BUFFER, self.ibo as u32);
            }
            gl::UseProgram(self.prog as u32);
            gl::Viewport(self.viewport[0], self.viewport[1], self.viewport[2], self.viewport[3]);
            gl::ColorMask(self.color_mask[0], self.color_mask[1], self.color_mask[2], self.color_mask[3]);
            gl::ClearColor(self.clear_color[0], self.clear_color[1], self.clear_color[2], self.clear_color[3]);
            gl::ClearDepth(self.clear_depth);
            if self.cull_face != 0 { gl::Enable(gl::CULL_FACE); } else { gl::Disable(gl::CULL_FACE); }
            if self.depth_test != 0 { gl::Enable(gl::DEPTH_TEST); } else { gl::Disable(gl::DEPTH_TEST); }
            gl::DepthFunc(self.depth_func as u32);
            gl::DepthMask(self.depth_mask);
            if self.blend != 0 { gl::Enable(gl::BLEND); } else { gl::Disable(gl::BLEND); }
        }
    }
}

// Drains and reports the GL error queue. Called around our draw so an error raised here is not
// mistaken for one of Minecraft's (and vice versa). Bounded so a stuck context cannot spam forever.
fn drain_gl_errors(label: &str) {
    unsafe {
        for _ in 0..10 {
            let err = gl::GetError();
            if err == gl::NO_ERROR {
                return;
            }
            println!("[MIA map-native] GL error at {}: 0x{:X}", label, err);
        }
    }
}

// Shared between the worker (stage) and render (render) threads. Both mutexes make Ctx Sync, so the
// raw handle can be dereferenced as &Ctx from either thread. GL is only ever touched under `gl` on
// the render thread; the worker only touches `pending`.
pub struct Ctx {
    // PERSISTENT per-shell meshes, indexed by slot (0 = innermost). Shells shift at different rates
    // — a 4-block shell moves every 4 blocks of pan, a 32-block shell every 32 — so re-meshing the
    // whole cascade whenever any one moved would cost more than the single grid it replaces. Slots
    // let the worker re-mesh only what actually changed and keep the rest.
    slots: Mutex<Vec<Option<PendingMesh>>>,
    pending: Mutex<Option<Vec<PendingMesh>>>,
    gl: Mutex<GlState>,
}

pub fn create() -> Box<Ctx> {
    let mut fbo = 0u32;
    unsafe {
        gl::GenFramebuffers(1, &mut fbo);
    }
    Box::new(Ctx {
        slots: Mutex::new(Vec::new()),
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

// Whether a render() call would put pixels on screen: geometry is already uploaded, OR the worker
// has staged a mesh that render() will adopt before drawing. Gating the draw on uploaded geometry
// ALONE deadlocks — the upload happens inside render(), so the mesh could never become drawable.
pub fn has_content(ctx: &Ctx) -> bool {
    if ctx.gl.lock().map(|g| g.index_count > 0).unwrap_or(false) {
        return true;
    }
    // Must be REAL geometry, not merely "a cascade was staged": an empty commit is Some(vec![]), and
    // reporting that as content would let the GPU path take over with nothing to draw — exactly the
    // black-on-open failure fixed in cd7e65c. Keep the CPU render up until the GPU can truly draw.
    ctx.pending
        .lock()
        .map(|p| {
            p.as_ref()
                .map_or(false, |shells| shells.iter().any(|m| !m.indices.is_empty()))
        })
        .unwrap_or(false)
}

// WORKER thread: publish the latest finished mesh, dropping any older un-uploaded one. No GL.
// WORKER thread. Replace one shell's mesh; slots persist, so shells the caller did not re-mesh keep
// the geometry they already had.
pub fn set_shell(ctx: &Ctx, slot: usize, mesh: PendingMesh) {
    let mut slots = ctx.slots.lock().unwrap();
    if slot >= slots.len() {
        slots.resize_with(slot + 1, || None);
    }
    slots[slot] = Some(mesh);
}

// WORKER thread. Publish the first `count` slots as one frame. Atomic, so the render thread never
// picks up a half-updated cascade. Trailing slots are dropped, so a cascade that shrinks (zooming
// out to fewer shells) does not leave stale geometry behind.
pub fn commit(ctx: &Ctx, count: usize) {
    let mut slots = ctx.slots.lock().unwrap();
    slots.truncate(count.max(0));
    let shells: Vec<PendingMesh> = slots
        .iter()
        .flatten()
        .map(|m| PendingMesh {
            verts: m.verts.clone(),
            colors: m.colors.clone(),
            indices: m.indices.clone(),
            cell: m.cell,
            origin: m.origin,
        })
        .collect();
    *ctx.pending.lock().unwrap() = Some(shells);
}

// RENDER thread: adopt a staged mesh (GL upload) if one is waiting, then draw.
// cut: [ox, oy, oz, nx, ny, nz, on] in shifted-block space; on = 0 disables the plane.
pub fn render(ctx: &Ctx, mvp: &[f32; 16], cut: &[f32; 7], tex_id: u32, w: i32, h: i32) {
    drain_gl_errors("render start (pre-existing)");
    let staged = ctx.pending.lock().unwrap().take();
    let mut g = ctx.gl.lock().unwrap();
    // Restores every GL state we touch when this scope exits, including the early return below.
    let _guard = unsafe { GlStateGuard::new() };
    if let Some(shells) = staged {
        let merged = merge(&shells);
        unsafe {
            upload(&mut g, &merged);
        }
    }
    if g.program == 0 || g.vao == 0 || g.index_count == 0 {
        return;
    }
    unsafe {
        draw(&mut g, mvp, cut, tex_id, w, h);
    }
    drain_gl_errors("render end");
}

// Flatten a cascade into ONE mesh, so the draw path stays exactly as it was: one VAO, one buffer
// set, one clear, one draw. This is deliberate — the depth-func/blend setup, the per-frame FBO
// detach and GlStateGuard were each hard-won against real corruption, and drawing N times would put
// that discipline back in play N times over. The rule stands: inherit nothing, leave nothing.
//
// Shells have DIFFERENT cell sizes, and the shader reconstructs position as (uOrigin + aPos)*uCell
// from per-mesh uniforms — so they cannot share a buffer while vertices are in cell units. Baking
// each shell's vertices to world space here makes the geometry self-describing and lets any mix of
// levels merge. The uniforms are then set to identity by the caller.
//
// Vertex layout is interleaved [px,py,pz, nx,ny,nz]; only position is transformed, normals are
// direction-only and unaffected by the uniform scale and translation.
fn merge(shells: &[PendingMesh]) -> PendingMesh {
    if shells.len() == 1 && shells[0].cell == 1.0 && shells[0].origin == [0.0, 0.0, 0.0] {
        // Already world-space and alone: nothing to do.
        return PendingMesh {
            verts: shells[0].verts.clone(),
            colors: shells[0].colors.clone(),
            indices: shells[0].indices.clone(),
            cell: 1.0,
            origin: [0.0, 0.0, 0.0],
        };
    }
    let nv: usize = shells.iter().map(|s| s.verts.len()).sum();
    let nc: usize = shells.iter().map(|s| s.colors.len()).sum();
    let ni: usize = shells.iter().map(|s| s.indices.len()).sum();
    let mut verts = Vec::with_capacity(nv);
    let mut colors = Vec::with_capacity(nc);
    let mut indices = Vec::with_capacity(ni);

    for s in shells {
        // Indices are per-shell 0-based; offset by the vertices already emitted.
        let base = (verts.len() / 6) as u32;
        let mut v = 0usize;
        while v + 5 < s.verts.len() {
            verts.push((s.origin[0] + s.verts[v]) * s.cell);
            verts.push((s.origin[1] + s.verts[v + 1]) * s.cell);
            verts.push((s.origin[2] + s.verts[v + 2]) * s.cell);
            verts.push(s.verts[v + 3]);
            verts.push(s.verts[v + 4]);
            verts.push(s.verts[v + 5]);
            v += 6;
        }
        colors.extend_from_slice(&s.colors);
        indices.extend(s.indices.iter().map(|i| i + base));
    }
    PendingMesh { verts, colors, indices, cell: 1.0, origin: [0.0, 0.0, 0.0] }
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

unsafe fn draw(g: &mut GlState, mvp: &[f32; 16], cut: &[f32; 7], tex_id: u32, w: i32, h: i32) {
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
    // into the freshly-attached FBO (the texture came back blank). Keep the query even if the result
    // is only logged. Clear OPAQUE black — a transparent clear also blanks the texture here.
    let status = gl::CheckFramebufferStatus(gl::FRAMEBUFFER);
    if status != gl::FRAMEBUFFER_COMPLETE {
        println!("[MIA map-native] FBO incomplete (0x{:X}), skipping draw", status);
        return;
    }
    // Never inherit depth/blend state from Minecraft. It leaves its own DepthFunc bound (reversed-Z
    // in places) and blending enabled, either of which silently rejects or erases every fragment —
    // a clean, error-free draw that outputs nothing. GlStateGuard restores all of this on exit.
    gl::ClearDepth(1.0);
    gl::DepthFunc(gl::LEQUAL);
    gl::DepthMask(gl::TRUE);
    gl::Disable(gl::BLEND);
    gl::ClearColor(0.0, 0.0, 0.0, 1.0);
    gl::Clear(gl::COLOR_BUFFER_BIT | gl::DEPTH_BUFFER_BIT);

    gl::UseProgram(g.program);

    let name_mvp = CString::new("uMVP").unwrap();
    let name_cell = CString::new("uCell").unwrap();
    let name_origin = CString::new("uOrigin").unwrap();
    let name_cut_o = CString::new("uCutOrigin").unwrap();
    let name_cut_n = CString::new("uCutNormal").unwrap();
    let name_cut_on = CString::new("uCutOn").unwrap();
    let loc_mvp = gl::GetUniformLocation(g.program, name_mvp.as_ptr());
    let loc_cell = gl::GetUniformLocation(g.program, name_cell.as_ptr());
    let loc_origin = gl::GetUniformLocation(g.program, name_origin.as_ptr());
    let loc_cut_o = gl::GetUniformLocation(g.program, name_cut_o.as_ptr());
    let loc_cut_n = gl::GetUniformLocation(g.program, name_cut_n.as_ptr());
    let loc_cut_on = gl::GetUniformLocation(g.program, name_cut_on.as_ptr());

    gl::UniformMatrix4fv(loc_mvp, 1, gl::FALSE, mvp.as_ptr());
    gl::Uniform1f(loc_cell, g.cell);
    gl::Uniform3f(loc_origin, g.origin[0], g.origin[1], g.origin[2]);
    gl::Uniform3f(loc_cut_o, cut[0], cut[1], cut[2]);
    gl::Uniform3f(loc_cut_n, cut[3], cut[4], cut[5]);
    gl::Uniform1f(loc_cut_on, cut[6]);

    gl::BindVertexArray(g.vao);
    gl::DrawElements(gl::TRIANGLES, g.index_count, gl::UNSIGNED_INT, std::ptr::null());
    gl::BindVertexArray(0);

    // Release Minecraft's texture before returning. Leaving it bound to COLOR_ATTACHMENT0 keeps our
    // FBO holding a handle on a texture MC samples every frame, which is a feedback-loop hazard and
    // was corrupting unrelated textures (map murals rendered with font-atlas content).
    gl::FramebufferTexture2D(gl::FRAMEBUFFER, gl::COLOR_ATTACHMENT0, gl::TEXTURE_2D, 0, 0);

    gl::UseProgram(0);
    gl::BindFramebuffer(gl::FRAMEBUFFER, prev as u32);
    gl::Disable(gl::DEPTH_TEST);
}
