package com.mia.loddy.api;

import com.mia.aperture.lod.LodNative;

/**
 * Public service API exposed by the standalone mia-loddy mod.
 * External mods (such as mia-mappy) can check FabricLoader.getInstance().isModLoaded("lod_native")
 * and query LodService.getInstance() to interact with distance LOD storage and rendering.
 */
public final class LodService {
    private static final LodService INSTANCE = new LodService();

    private long handle = 0;
    private volatile boolean worldRenderingEnabled = true;
    private volatile int statDrawn = 0;
    private volatile int statQuads = 0;

    private LodService() {}

    public static LodService getInstance() {
        return INSTANCE;
    }

    public int getStatDrawn() {
        return statDrawn;
    }

    public int getStatQuads() {
        return statQuads;
    }

    public void setStats(int drawn, int quads) {
        this.statDrawn = drawn;
        this.statQuads = quads;
    }

    public boolean isAvailable() {
        return LodNative.available() && handle != 0;
    }

    public synchronized void setStoreHandle(long handle) {
        this.handle = handle;
    }

    public long getStoreHandle() {
        return handle;
    }

    public boolean isWorldRenderingEnabled() {
        return worldRenderingEnabled && isAvailable();
    }

    public void setWorldRenderingEnabled(boolean enabled) {
        this.worldRenderingEnabled = enabled;
    }

    public boolean getSection(int level, int x, int y, int z, int[] idsOut, int[] biomesOut) {
        if (!isAvailable()) return false;
        return LodNative.nGet(handle, level, x, y, z, idsOut, biomesOut);
    }

    public boolean indexSection(int x, int y, int z, int[] ids, int[] biomes) {
        if (!isAvailable()) return false;
        return LodNative.nIndex(handle, x, y, z, ids, biomes);
    }

    public long meshSection(int level, int x, int y, int z, int[] vertexOut, int[] indexOut) {
        if (!isAvailable()) return 0;
        return LodNative.nMeshSection(handle, level, x, y, z, vertexOut, indexOut);
    }

    public int planCascade(float camX, float camY, float camZ, float viewDist, int minY, int maxY, int[] tilesOut) {
        if (!isAvailable()) return 0;
        return LodNative.nPlanCascade(camX, camY, camZ, viewDist, minY, maxY, tilesOut);
    }

    public String getBlockKey(int id) {
        if (!isAvailable()) return null;
        return LodNative.nBlockKey(handle, id);
    }

    public int internBlock(String key, int flags) {
        if (!isAvailable()) return LodNative.AIR;
        return LodNative.nInternBlock(handle, key, flags);
    }
}
