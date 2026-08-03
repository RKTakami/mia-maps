package com.mia.aperture.lod;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Native JNI bridge for mia-loddy distance LOD storage, meshing, and cascade planning.
 */
public final class LodNative {
    public static final int EDGE = 16;
    public static final int CELLS = EDGE * EDGE * EDGE;
    public static final int AIR = 0;

    public static final int BIOME_EDGE = 4;
    public static final int BIOME_CELLS = BIOME_EDGE * BIOME_EDGE * BIOME_EDGE;
    public static final String BIOME_PREFIX = "biome:";

    public static final int FLAG_OPAQUE = 1;
    public static final int FLAG_WATER = 1 << 1;
    public static final int FLAG_FOLIAGE = 1 << 2;

    public static final int EXPECTED_VERSION = 3;

    private static boolean available = false;

    private LodNative() {}

    public static synchronized void ensureLoaded() {
        if (available) return;
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String lib = os.contains("win") ? "lod_native.dll"
                       : os.contains("mac") ? "liblod_native.dylib" : "liblod_native.so";
            String res = "/natives/" + lib;
            try (InputStream in = LodNative.class.getResourceAsStream(res)) {
                if (in == null) throw new RuntimeException("native not found: " + res);
                Path tmp = Files.createTempFile("lod_native", lib.substring(lib.lastIndexOf('.')));
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                System.load(tmp.toAbsolutePath().toString());
            }
            int v = nVersion();
            if (v != EXPECTED_VERSION) {
                throw new RuntimeException("format version " + v + ", expected " + EXPECTED_VERSION);
            }
            available = true;
            System.out.println("[MIA Loddy] native library loaded, format version " + v);
        } catch (Throwable t) {
            available = false;
            System.err.println("[MIA Loddy] native library unavailable (LOD store disabled): " + t);
        }
    }

    public static boolean available() { return available; }

    public static native int nVersion();
    public static native long nOpen(String path);
    public static native void nClose(long handle);
    public static native boolean nGet(long handle, int level, int x, int y, int z, int[] idsOut, int[] biomesOut);
    public static native boolean nIndex(long handle, int x, int y, int z, int[] ids, int[] biomes);
    public static native void nFlush(long handle);
    public static native int nInternBlock(long handle, String key, int flags);
    public static native String nBlockKey(long handle, int id);
    public static native long nSkipped(long handle);
    public static native long nMeshSection(long handle, int level, int x, int y, int z, int[] vertexOut, int[] indexOut);
    public static native int nPlanCascade(float camX, float camY, float camZ, float viewDist, int minY, int maxY, int[] tilesOut);
}
