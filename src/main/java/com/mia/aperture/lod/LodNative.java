package com.mia.aperture.lod;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Java side of the {@code mia-loddy} level-of-detail store.
 *
 * <p>Loading is <b>optional by design</b>. The store is being introduced alongside the existing data
 * path, so a build without the native must leave the mod working exactly as before rather than
 * failing — {@link #available()} stays false and callers skip the feature.
 *
 * <p>Cells hold <b>block ids</b>, not colours. Ids come from {@link #internBlock}, are anchored to a
 * block state's canonical string, and are stable across sessions and game versions — unlike the
 * game's own runtime ids, which move with the version and the mod set. Colour, per-face shading and
 * biome tint stay here in the mod, resolved at read time, so how blocks look can change without
 * re-indexing anything.
 */
public final class LodNative {
    /** Cells per section axis; matches a vanilla chunk section. */
    public static final int EDGE = 16;
    /** Cells per section. Array arguments must be exactly this long. */
    public static final int CELLS = EDGE * EDGE * EDGE;
    /** Reserved id meaning "nothing here". Never returned by {@link #nInternBlock}. */
    public static final int AIR = 0;

    /** Biome cells per section axis. Minecraft resolves biomes at 4x4x4, and this mirrors it. */
    public static final int BIOME_EDGE = 4;
    /** Biome cells per section — 64, against 4096 block cells. */
    public static final int BIOME_CELLS = BIOME_EDGE * BIOME_EDGE * BIOME_EDGE;
    /** Prefix distinguishing biome keys from block keys in the shared id table. */
    public static final String BIOME_PREFIX = "biome:";

    // Block-type flags, mirroring the store's own. Describe a block TYPE once, not a cell.
    public static final int FLAG_OPAQUE = 1;
    public static final int FLAG_WATER = 1 << 1;
    public static final int FLAG_FOLIAGE = 1 << 2;

    /** On-disk format this build understands. */
    public static final int EXPECTED_VERSION = 3;

    private static boolean available = false;

    private LodNative() {}

    public static synchronized void ensureLoaded() {
        if (available) return;
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String lib = os.contains("win") ? "mia_loddy.dll"
                       : os.contains("mac") ? "libmia_loddy.dylib" : "libmia_loddy.so";
            String res = "/natives/" + lib;
            try (InputStream in = LodNative.class.getResourceAsStream(res)) {
                if (in == null) throw new RuntimeException("native not found: " + res);
                Path tmp = Files.createTempFile("mia_loddy", lib.substring(lib.lastIndexOf('.')));
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                System.load(tmp.toAbsolutePath().toString());
            }
            int v = nVersion();
            if (v != EXPECTED_VERSION) {
                // Refuse rather than misread: a format we do not understand would be interpreted as
                // terrain, not rejected as garbage.
                throw new RuntimeException("format version " + v + ", expected " + EXPECTED_VERSION);
            }
            available = true;
            System.out.println("[MIA Maps] mia-loddy loaded, format version " + v);
        } catch (Throwable t) {
            available = false;
            System.err.println("[MIA Maps] mia-loddy unavailable (LOD store disabled): " + t);
        }
    }

    public static boolean available() { return available; }

    private static native int nVersion();

    /** @return handle, or 0 on failure. */
    public static native long nOpen(String path);

    /** Safe with 0. Must not race any other call on the same handle. */
    public static native void nClose(long handle);

    /**
     * Stable id for a block state's canonical string, creating one if needed.
     *
     * <p>Re-interning an existing key updates its flags, so refining a classification costs nothing.
     *
     * @return the id, or 0 on failure — and 0 is also air, so a caller that ignores the failure
     *         records air rather than mislabelled terrain.
     */
    public static native int nInternBlock(long handle, String key, int flags);

    /** Canonical string for an id, or null if it was never interned. */
    public static native String nBlockKey(long handle, int id);

    /**
     * Read a section into {@code idsOut} ({@link #CELLS} long) and {@code biomesOut}
     * ({@link #BIOME_CELLS} long).
     *
     * <p>{@code biomesOut} is zeroed for terrain indexed before biomes were recorded — meaning
     * "unknown", so a caller tints with a default rather than with a stale array.
     *
     * @return true when the section exists. <b>False means never seen</b> — a section that has been
     *         seen and is empty returns true with an all-air array. Callers need that distinction.
     */
    public static native boolean nGet(long handle, int level, int x, int y, int z, int[] idsOut, int[] biomesOut);

    /**
     * Record a level-0 section. <b>Does not fold the pyramid</b> — call {@link #nFlush} periodically
     * while indexing and once when done, or coarse levels stay stale.
     */
    public static native boolean nIndex(long handle, int x, int y, int z, int[] ids, int[] biomes);

    /** Fold every parent marked dirty. @return how many were rebuilt, or -1 on failure. */
    public static native int nFlush(long handle);

    /** Parents awaiting a rebuild, or -1 on failure. */
    public static native long nDirtyCount(long handle);

    /** Sections stored across all levels, or -1 on failure. */
    public static native long nLen(long handle);

    /** Sections skipped as unchanged since the store was opened, or -1 on failure. */
    public static native long nSkipped(long handle);
}
