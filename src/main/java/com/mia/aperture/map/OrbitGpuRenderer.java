package com.mia.aperture.map;

public final class OrbitGpuRenderer {
    private static long ctx;
    private static volatile VoxelCloud.Grid pending;
    private static VoxelCloud.Grid uploaded;

    private OrbitGpuRenderer() {}

    public static void submit(VoxelCloud.Grid g) { pending = g; }

    public static boolean render(float[] mvp, int texId, int size) {
        if (!MapNative.available()) return false;
        MapNative.initGLOnce();
        if (ctx == 0) ctx = MapNative.nCreateContext();
        VoxelCloud.Grid g = pending;
        if (g != null && g != uploaded) {
            MapNative.nUploadGrid(ctx, g.opaque(), g.argb(), g.gX(), g.gY(), g.gZ(),
                    g.cell(), g.originCellX(), g.originCellY(), g.originCellZ());
            uploaded = g;
        }
        if (uploaded == null) return false;
        MapNative.nRender(ctx, mvp, texId, size, size);
        return true;
    }
}
