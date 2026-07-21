package com.mia.aperture.map;

import java.io.InputStream;
import java.nio.file.*;

public final class MapNative {
    private static boolean available = false;
    private static boolean glInited = false;
    private MapNative() {}

    public static synchronized void ensureLoaded() {
        if (available) return;
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String lib = os.contains("win") ? "map_native.dll"
                       : os.contains("mac") ? "libmap_native.dylib" : "libmap_native.so";
            String res = "/natives/" + lib;
            try (InputStream in = MapNative.class.getResourceAsStream(res)) {
                if (in == null) throw new RuntimeException("native not found: " + res);
                Path tmp = Files.createTempFile("map_native", lib.substring(lib.lastIndexOf('.')));
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                System.load(tmp.toAbsolutePath().toString());
            }
            if (!nInit()) throw new RuntimeException("nInit returned false");
            available = true;
            System.out.println("[MIA Maps] map-native loaded, version " + nVersion());
        } catch (Throwable t) {
            available = false;
            System.err.println("[MIA Maps] map-native unavailable, using CPU fallback: " + t);
        }
    }

    public static boolean available() { return available; }

    // Load GL symbols against MC's current context. Must run on the render thread (GL current).
    public static synchronized void initGLOnce() {
        if (glInited) return;
        nInitGL();
        glInited = true;
    }

    // Called back FROM native (nInitGL) to resolve GL symbols against MC's current context.
    public static long getGlAddress(String symbol) {
        return org.lwjgl.glfw.GLFW.glfwGetProcAddress(symbol);
    }

    private static native boolean nInit();
    private static native int nVersion();
    public static native void nInitGL();
    public static native long nCreateContext();
    public static native void nDestroyContext(long handle);
    public static native void nClear(long handle, int texId, int w, int h);
}
