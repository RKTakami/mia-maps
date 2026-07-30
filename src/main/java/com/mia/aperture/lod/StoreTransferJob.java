package com.mia.aperture.lod;

import com.mia.aperture.map.MapEngineSource;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bulk transfer between the Voxy store and ours, in either direction.
 *
 * <p>Import exists because this store only knows terrain walked since indexing was switched on,
 * while Voxy may have been accumulating for months. Export exists because the whole point of this
 * project is to feed renderers that read Voxy, so data captured here has to be able to go back.
 *
 * <p>Neither store can be enumerated — both are addressed by coordinate — so both directions walk a
 * bounded region around the player rather than the whole world. That is a real limitation, not a
 * simplification: terrain outside the radius is not transferred, and running it again from somewhere
 * else is how you cover more.
 *
 * <p><b>Export never overwrites.</b> It writes only sections Voxy does not already have. Voxy's
 * database is live — the map and any Voxy renderer are reading it while this runs — and our data is
 * a quarter-resolution reconstruction whose sector is ambiguous inside the Abyss overlap band. Given
 * that, filling gaps is defensible and replacing good data is not.
 */
public final class StoreTransferJob {
    private StoreTransferJob() {}

    /** Voxy sections each way. One is 32 blocks at level 0, so 24 covers ~768 blocks. */
    private static final int RADIUS = 24;
    private static final int V_RADIUS = 8;
    private static final int LEVEL = 0;
    private static final int VOXY_CELLS = StoreTransfer.VOXY_EDGE * StoreTransfer.VOXY_EDGE
            * StoreTransfer.VOXY_EDGE;

    private static final AtomicBoolean running = new AtomicBoolean();
    /** Last outcome, for the settings screen. Null until something has run. */
    public static volatile String lastResult;

    /**
     * How long the activity indicator keeps turning after the work has actually finished.
     *
     * <p>A transfer over an explored region takes about a second, and an indicator that appears and
     * vanishes inside that is a flicker rather than information. This lingers the DISPLAY only —
     * nothing waits on it and no work is slowed; {@link #busy()} still reports the truth for anything
     * that needs to know whether a job is running.
     */
    private static final long LINGER_MS = 900;
    private static volatile long finishedAt;

    /** Whether a transfer is genuinely running. Use this for anything that makes a claim. */
    public static boolean busy() { return running.get(); }

    /**
     * Whether to show activity: running, or recently finished.
     *
     * <p>Deliberately separate from {@link #busy()}. A button reading "Working..." while nothing runs
     * would be a false statement, so labels use busy(); the gears are an animation saying "the machine
     * just did something", which stays true across the linger.
     */
    public static boolean showActivity() {
        return running.get() || System.currentTimeMillis() - finishedAt < LINGER_MS;
    }

    /**
     * Say it in game, not just to the log.
     *
     * <p>The first version reported only to stdout, and both directions were run without any way to
     * tell whether anything had happened — the same failure as a silent diagnostic, where absence
     * reads as "nothing to report". Chat is where a player is actually looking.
     */
    private static void say(String msg) {
        System.out.println("[MIA Mappy] " + msg);
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("[MIA] " + msg), false);
            }
        });
    }

    /** Voxy block id -> our interned id. Voxy ids are small and dense, so an array beats a map. */
    private static int[] blockMap = new int[0];
    private static int[] biomeMap = new int[0];

    /** What a started job is doing. */
    private enum Mode { IMPORT_NEARBY, IMPORT_ALL, EXPORT }

    public static void startImport() { start(Mode.IMPORT_NEARBY); }
    public static void startFullImport() { start(Mode.IMPORT_ALL); }
    public static void startExport() { start(Mode.EXPORT); }

    /** Which job is running, for a label that can say so. Null when idle. */
    public static volatile String activity;

    private static void start(Mode mode) {
        if (!running.compareAndSet(false, true)) {
            say("a transfer is already running");
            return;
        }
        long handle = LodIndexer.handle();
        WorldEngine engine = MapEngineSource.get();
        if (handle == 0 || engine == null) {
            running.set(false);
            lastResult = "needs both stores open (loddy=" + (handle != 0)
                    + " voxy=" + (engine != null) + ")";
            say(lastResult);
            return;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            running.set(false);
            return;
        }
        // Snapshot the origin on the client thread: the walk must not read player position from
        // another thread, and a moving origin would make the covered region undefined.
        int px = (int) Math.floor(mc.player.getX());
        int py = (int) Math.floor(mc.player.getY());
        int pz = (int) Math.floor(mc.player.getZ());
        int sector = com.mia.aperture.map.MapGeometry.sectorForX(px);

        activity = switch (mode) {
            case IMPORT_NEARBY -> "Importing nearby";
            case IMPORT_ALL -> "Importing all";
            case EXPORT -> "Exporting";
        };
        Thread t = new Thread(() -> {
            try {
                switch (mode) {
                    case IMPORT_NEARBY -> runImport(handle, engine, px, py, pz, sector);
                    case IMPORT_ALL -> runFullImport(handle, engine);
                    case EXPORT -> runExport(handle, engine, px, py, pz, sector);
                }
            } catch (Throwable e) {
                System.err.println("[MIA Mappy] transfer failed: " + e);
                e.printStackTrace();
            } finally {
                finishedAt = System.currentTimeMillis();
                activity = null;
                running.set(false);
            }
        }, "MIA-Store-Transfer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * Import one Voxy section, if it exists, into the four-way-finer sections it maps to.
     *
     * <p>Shared by the bounded import and the whole-store one so the two cannot drift — the octant
     * conversion and the Abyss-band guard are exactly the fiddly parts that would silently differ if
     * they were written twice.
     *
     * @param counts {found, written, skipped}, accumulated
     */
    private static void importOne(long handle, WorldEngine engine, int vsx, int vsy, int vsz,
                                  long[] raw, int[] cells, int[] biomes,
                                  StoreTransfer.CellReader reader, int[] counts) {
        WorldSection s = engine.acquireIfExists(LEVEL, vsx, vsy, vsz);
        if (s == null) return;
        try {
            s.copyDataTo(raw);
        } finally {
            s.release();
        }
        counts[0]++;
        int[] base = StoreTransfer.voxyToOurs(LEVEL, vsx, vsy, vsz);
        if (base == null) { counts[2]++; return; }   // outside the Abyss band
        for (int oy = 0; oy < 2; oy++) {
            for (int oz = 0; oz < 2; oz++) {
                for (int ox = 0; ox < 2; ox++) {
                    if (!StoreTransfer.octantToOurs(raw, ox, oy, oz, reader, cells, biomes)) continue;
                    if (LodNative.nIndex(handle, base[0] + ox, base[1] + oy, base[2] + oz,
                            cells, biomes)) {
                        counts[1]++;
                    }
                }
            }
        }
    }

    /**
     * Import the WHOLE Voxy store, not a box around the player.
     *
     * <p>The bounded import exists because the project notes recorded that neither store could be
     * enumerated. That is true of ours and <b>not</b> true of Voxy: {@code WorldEngine.storage} is a
     * public {@code SectionStorage}, which implements {@code IStoredSectionPositionIterator}, and
     * {@code WorldEngine.getX/getY/getZ} unpack the ids it hands back. So the complete migration the
     * bounded version could only approximate is available directly.
     *
     * <p>This is what makes defaulting to our store defensible for someone who has been running Voxy
     * for months: after it, our store is a superset rather than a box.
     *
     * <p><b>Positions are collected before any are read.</b> Holding an iteration open across
     * hundreds of thousands of acquire/index round trips would keep a read transaction alive on a
     * database the game is still writing to, for minutes. Draining it into an array first costs 8
     * bytes a section — single-digit MB at the sizes involved — and keeps the borrow short.
     */
    private static void runFullImport(long handle, WorldEngine engine) {
        Mapper mapper = engine.getMapper();
        resetMaps(mapper);

        long t0 = System.currentTimeMillis();
        long before = LodNative.nLen(handle);
        say("import: enumerating the whole Voxy store...");

        it.unimi.dsi.fastutil.longs.LongArrayList positions = new it.unimi.dsi.fastutil.longs.LongArrayList();
        try {
            engine.storage.iteratePositions(LEVEL, positions::add);
        } catch (Throwable e) {
            // Not fatal, and worth saying rather than falling back silently: a bounded import is a
            // different result from a complete one, and the difference is exactly what the caller
            // asked for.
            say("import: could not enumerate the Voxy store (" + e + "). Nothing written.");
            return;
        }
        if (positions.isEmpty()) {
            say("import: the Voxy store reports no sections at level " + LEVEL + ". Nothing to do.");
            return;
        }
        say("import: " + positions.size() + " Voxy sections to read. This will take a while.");

        long[] raw = new long[VOXY_CELLS];
        int[] cells = new int[LodNative.CELLS];
        int[] biomes = new int[LodNative.BIOME_CELLS];
        StoreTransfer.CellReader reader = new StoreTransfer.CellReader() {
            @Override public boolean isAir(long c) { return Mapper.isAir(c); }
            @Override public int block(long c) { return ourBlock(handle, mapper, Mapper.getBlockId(c)); }
            @Override public int biome(long c) { return ourBiome(handle, mapper, Mapper.getBiomeId(c)); }
        };

        int[] counts = new int[3];
        int n = positions.size();
        int report = Math.max(5000, n / 10);
        for (int i = 0; i < n; i++) {
            long id = positions.getLong(i);
            importOne(handle, engine, WorldEngine.getX(id), WorldEngine.getY(id),
                    WorldEngine.getZ(id), raw, cells, biomes, reader, counts);
            // Progress, because a silent job that runs for minutes is indistinguishable from a hung
            // one — and this one legitimately runs for minutes.
            if ((i + 1) % report == 0) {
                say(String.format("import: %d%% (%d/%d read, %d written)",
                        (i + 1) * 100 / n, counts[0], n, counts[1]));
            }
            if ((i + 1) % 20000 == 0) LodNative.nFlush(handle);
        }

        LodNative.nFlush(handle);   // fold the pyramid, or coarse zooms stay empty
        long after = LodNative.nLen(handle);
        lastResult = "full import: read " + counts[0] + " of " + n + " Voxy sections, wrote "
                + counts[1] + ", store " + before + " -> " + after + " ("
                + (after - before >= 0 ? "+" : "") + (after - before) + " new) in "
                + (System.currentTimeMillis() - t0) / 1000 + "s"
                + (counts[2] > 0 ? ", " + counts[2] + " outside the Abyss band" : "");
        say(lastResult);
    }

    // ---- import: Voxy -> ours -------------------------------------------------------------------

    private static void runImport(long handle, WorldEngine engine, int px, int py, int pz, int sector) {
        Mapper mapper = engine.getMapper();
        resetMaps(mapper);
        int voxySpan = StoreTransfer.VOXY_EDGE << LEVEL;
        // Voxy is keyed in shifted space, so the origin has to be shifted before walking it.
        int sx0 = Math.floorDiv(com.mia.aperture.map.MapGeometry.shiftX(px, sector), voxySpan);
        int sy0 = Math.floorDiv(com.mia.aperture.map.MapGeometry.shiftY(py, sector), voxySpan);
        int sz0 = Math.floorDiv(pz, voxySpan);

        long[] raw = new long[VOXY_CELLS];
        int[] cells = new int[LodNative.CELLS];
        int[] biomes = new int[LodNative.BIOME_CELLS];
        StoreTransfer.CellReader reader = new StoreTransfer.CellReader() {
            @Override public boolean isAir(long c) { return Mapper.isAir(c); }
            @Override public int block(long c) { return ourBlock(handle, mapper, Mapper.getBlockId(c)); }
            @Override public int biome(long c) { return ourBiome(handle, mapper, Mapper.getBiomeId(c)); }
        };

        long t0 = System.currentTimeMillis();
        int found = 0, written = 0, skipped = 0;
        long before = LodNative.nLen(handle);
        say("import: scanning " + (2 * RADIUS + 1) + "x" + (2 * V_RADIUS + 1) + "x"
                + (2 * RADIUS + 1) + " Voxy sections around you...");

        int[] counts = new int[3];   // found, written, skipped
        for (int dy = -V_RADIUS; dy <= V_RADIUS; dy++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    importOne(handle, engine, sx0 + dx, sy0 + dy, sz0 + dz, raw, cells, biomes,
                            reader, counts);
                }
            }
        }
        found = counts[0]; written = counts[1]; skipped = counts[2];
        LodNative.nFlush(handle);   // fold the pyramid, or coarse zooms stay empty
        long after = LodNative.nLen(handle);
        // Store total before and after is the answer to "did that do anything". Sections written
        // alone cannot tell work done from work already present, because indexing dedups by content
        // hash — re-importing the same region reports the same count and changes nothing.
        lastResult = "import: read " + found + " Voxy sections, wrote " + written + ", store "
                + before + " -> " + after + " (" + (after - before >= 0 ? "+" : "")
                + (after - before) + " new) in " + (System.currentTimeMillis() - t0) + "ms"
                + (skipped > 0 ? ", " + skipped + " outside the Abyss band" : "");
        say(lastResult);
    }

    // ---- export: ours -> Voxy -------------------------------------------------------------------

    private static void runExport(long handle, WorldEngine engine, int px, int py, int pz, int sector) {
        Mapper mapper = engine.getMapper();
        LodBlockTable table = LodIndexer.blockTable();
        int span = StoreTransfer.EDGE << LEVEL;
        int sx0 = Math.floorDiv(px, span), sy0 = Math.floorDiv(py, span), sz0 = Math.floorDiv(pz, span);

        int[] ids = new int[LodNative.CELLS];
        int[] biomes = new int[LodNative.BIOME_CELLS];
        long t0 = System.currentTimeMillis();
        int read = 0, created = 0, alreadyThere = 0, unresolved = 0;
        // 2x the radius in our units, since our sections are half the span of Voxy's.
        int r = RADIUS * 2, vr = V_RADIUS * 2;
        say("export: filling gaps in Voxy around you (never overwrites)...");

        for (int dy = -vr; dy <= vr; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int sx = sx0 + dx, sy = sy0 + dy, sz = sz0 + dz;
                    if (!LodNative.nGet(handle, LEVEL, sx, sy, sz, ids, biomes)) continue;
                    read++;
                    int[] o = StoreTransfer.octantOf(LEVEL, sx, sy, sz, sector);
                    // Never overwrite: Voxy's database is live and its data is full resolution,
                    // while ours is a reconstruction with an ambiguous sector in the overlap band.
                    WorldSection existing = engine.acquireIfExists(LEVEL, o[3], o[4], o[5]);
                    if (existing != null) {
                        existing.release();
                        alreadyThere++;
                        continue;
                    }
                    WorldSection dst = engine.acquire(LEVEL, o[3], o[4], o[5]);
                    if (dst == null) continue;
                    try {
                        boolean wroteAny = false;
                        for (int y = 0; y < StoreTransfer.EDGE; y++) {
                            for (int z = 0; z < StoreTransfer.EDGE; z++) {
                                for (int x = 0; x < StoreTransfer.EDGE; x++) {
                                    int id = ids[(y * StoreTransfer.EDGE + z) * StoreTransfer.EDGE + x];
                                    if (id == LodNative.AIR) continue;
                                    var state = table.stateFor(id);
                                    if (state == null) { unresolved++; continue; }
                                    int vBlock = mapper.getIdForBlockState(state);
                                    dst.set(o[0] * StoreTransfer.EDGE + x,
                                            o[1] * StoreTransfer.EDGE + y,
                                            o[2] * StoreTransfer.EDGE + z,
                                            Mapper.withBlockBiome(Mapper.AIR, vBlock, 0));
                                    wroteAny = true;
                                }
                            }
                        }
                        if (wroteAny) {
                            dst.markDirty();
                            engine.saveSection(dst);
                            created++;
                        }
                    } finally {
                        dst.release();
                    }
                }
            }
        }
        lastResult = "export: read " + read + " of ours, created " + created + " Voxy sections, "
                + alreadyThere + " already present"
                + (unresolved > 0 ? ", " + unresolved + " cells dropped (unresolved state)" : "")
                + " in " + (System.currentTimeMillis() - t0) + "ms"
                + (created == 0 && alreadyThere > 0 ? " — Voxy already had it all" : "");
        say(lastResult);
    }

    // ---- identity translation ------------------------------------------------------------------

    private static void resetMaps(Mapper mapper) {
        blockMap = new int[Math.max(1024, mapper.getBlockStateCount() + 1)];
        java.util.Arrays.fill(blockMap, -1);
        Mapper.BiomeEntry[] entries = mapper.getBiomeEntries();
        int max = 0;
        for (Mapper.BiomeEntry e : entries) max = Math.max(max, e.id);
        biomeMap = new int[max + 2];
        java.util.Arrays.fill(biomeMap, -1);
    }

    /** Voxy block id to ours, via a block state and our canonical key. Cached: interning is a JNI call. */
    private static int ourBlock(long handle, Mapper mapper, int voxyId) {
        if (voxyId < 0) return LodNative.AIR;
        if (voxyId >= blockMap.length) {
            blockMap = java.util.Arrays.copyOf(blockMap, voxyId + 64);
            java.util.Arrays.fill(blockMap, voxyId, blockMap.length, -1);
        }
        int cached = blockMap[voxyId];
        if (cached >= 0) return cached;
        int resolved = LodNative.AIR;
        try {
            var state = mapper.getBlockStateFromBlockId(voxyId);
            if (state != null) {
                // Same flags the live indexer records, so an imported block behaves identically to
                // one captured from a chunk — opacity and water drive the map's surface scan.
                resolved = LodNative.nInternBlock(handle, BlockKeys.of(state),
                        BlockFlags.of(state));
            }
        } catch (Throwable ignored) {
            // A state Voxy cannot resolve is dropped rather than stored as id 0, which would read
            // back as solid nothing and punch holes in otherwise good terrain.
        }
        blockMap[voxyId] = resolved;
        return resolved;
    }

    private static int ourBiome(long handle, Mapper mapper, int voxyBiomeId) {
        if (voxyBiomeId < 0 || voxyBiomeId >= biomeMap.length) return 0;
        int cached = biomeMap[voxyBiomeId];
        if (cached >= 0) return cached;
        int resolved = 0;
        for (Mapper.BiomeEntry e : mapper.getBiomeEntries()) {
            if (e.id == voxyBiomeId && e.biome != null) {
                resolved = LodNative.nInternBlock(handle, LodNative.BIOME_PREFIX + e.biome, 0);
                break;
            }
        }
        biomeMap[voxyBiomeId] = resolved;
        return resolved;
    }
}
