package com.mia.aperture.map;

// Two-thread GPU handoff. The WORKER thread greedy-meshes a grid off the render thread (submit ->
// nMeshGrid, pure CPU) and stages it in the native context; the RENDER thread only uploads the staged
// mesh + draws (render -> nRender). So a big/fine re-mesh never hitches the frame.
public final class OrbitGpuRenderer {
    private static volatile long ctx;      // created on the render thread (needs a GL context)
    private static long meshedSig = Long.MIN_VALUE;

    private OrbitGpuRenderer() {}

    // WORKER thread. Meshes the grid once per region (keyed by sig); retries once the context exists.
    public static void submit(VoxelCloud.Grid g, long sig) {
        if (g == null || !MapNative.available()) return;
        long c = ctx;
        if (c == 0 || sig == meshedSig) return;
        MapNative.nMeshGrid(c, g.opaque(), g.argb(), g.gX(), g.gY(), g.gZ(),
                g.cell(), g.originCellX(), g.originCellY(), g.originCellZ());
        meshedSig = sig;
    }

    // RENDER thread. Creates the GL context on first use, then uploads any staged mesh + draws.
    public static boolean render(float[] mvp, int texId, int size) {
        if (!MapNative.available()) return false;
        MapNative.initGLOnce();
        if (ctx == 0) ctx = MapNative.nCreateContext();
        MapNative.nRender(ctx, mvp, texId, size, size);
        return true;
    }
}
