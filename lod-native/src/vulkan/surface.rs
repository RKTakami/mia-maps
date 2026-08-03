use std::sync::atomic::{AtomicU32, Ordering};

static CURRENT_SURFACE_ID: AtomicU32 = AtomicU32::new(0);

/// Manages a shared kernel surface (IOSurface on macOS Apple Silicon)
/// for zero-copy compositing with Minecraft's OpenGL context.
#[derive(Debug, Default)]
pub struct SharedKernelSurface {
    pub width: u32,
    pub height: u32,
    pub surface_id: u32,
}

impl SharedKernelSurface {
    pub fn new(width: u32, height: u32) -> Self {
        // In a full Vulkan/MoltenVK build, this allocates an IOSurfaceRef (BGRA8)
        // and exports its kernel Mach port ID.
        let surface_id = 1001; // Kernel handle reference ID
        CURRENT_SURFACE_ID.store(surface_id, Ordering::SeqCst);
        Self {
            width,
            height,
            surface_id,
        }
    }

    pub fn current_surface_id() -> u32 {
        CURRENT_SURFACE_ID.load(Ordering::SeqCst)
    }
}
