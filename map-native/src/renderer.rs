use crate::gl;

pub struct Ctx {
    fbo: u32,
}

pub fn create() -> Box<Ctx> {
    let mut fbo = 0u32;
    unsafe {
        gl::GenFramebuffers(1, &mut fbo);
    }
    Box::new(Ctx { fbo })
}

pub fn destroy(ctx: Box<Ctx>) {
    unsafe {
        gl::DeleteFramebuffers(1, &ctx.fbo);
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
