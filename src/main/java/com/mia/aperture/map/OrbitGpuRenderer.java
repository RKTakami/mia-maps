package com.mia.aperture.map;

// Two-thread GPU handoff. The WORKER thread greedy-meshes a grid off the render thread (submit ->
// nMeshGrid, pure CPU) and stages it in the native context; the RENDER thread only uploads the staged
// mesh + draws (render -> nRender). So a big/fine re-mesh never hitches the frame.
public final class OrbitGpuRenderer {
    private static volatile long ctx;      // created on the render thread (needs a GL context)
    private static long meshedSig = Long.MIN_VALUE;

    // True when a draw would put pixels on screen: geometry uploaded, OR a staged mesh waiting that
    // render() adopts before drawing. Testing uploaded geometry alone deadlocks, since the upload
    // only happens inside render(). Until this is true the caller must keep showing the CPU render.
    public static boolean hasGeometry() {
        long c = ctx;
        return c != 0 && MapNative.available() && MapNative.nHasContent(c);
    }

    private OrbitGpuRenderer() {}

    // WORKER thread. Meshes the grid once per region (keyed by sig); retries once the context exists.
    public static void submit(VoxelCloud.Grid g, long sig) {
        if (g == null) return;
        submit(java.util.List.of(g), sig);
    }

    // WORKER thread. Stages a whole cascade — innermost shell first — as ONE atomic frame. Shells of
    // differing cell sizes are merged natively into a single buffer, so the draw path is unchanged:
    // one clear, one draw. A single-element list is exactly the old behaviour.
    public static void submit(java.util.List<VoxelCloud.Grid> shells, long sig) {
        if (shells == null || shells.isEmpty() || !MapNative.available()) return;
        long c = ctx;
        if (c == 0 || sig == meshedSig) return;
        MapNative.nMeshBegin(c);
        for (VoxelCloud.Grid g : shells) {
            if (g == null) continue;
            MapNative.nMeshGrid(c, g.opaque(), g.argb(), g.gX(), g.gY(), g.gZ(),
                    g.cell(), g.originCellX(), g.originCellY(), g.originCellZ());
        }
        MapNative.nMeshCommit(c);
        meshedSig = sig;
    }

    // RENDER thread. Creates the GL context if absent. MUST be called independently of the draw:
    // the worker cannot mesh until a context exists, and hasGeometry() gates the draw on a mesh, so
    // creating the context inside render() alone would deadlock the GPU path into never starting.
    public static void ensureContext() {
        if (!MapNative.available() || ctx != 0) return;
        MapNative.initGLOnce();
        ctx = MapNative.nCreateContext();
    }

    // RENDER thread. Uploads any staged mesh + draws.
    public static boolean render(float[] mvp, int texId, int size) {
        if (!MapNative.available()) return false;
        ensureContext();
        if (ctx == 0) return false;
        MapNative.nRender(ctx, mvp, texId, size, size);
        return true;
    }
}
