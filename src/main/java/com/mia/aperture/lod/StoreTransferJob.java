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

    public static boolean busy() { return running.get(); }

    /**
     * Say it in game, not just to the log.
     *
     * <p>The first version reported only to stdout, and both directions were run without any way to
     * tell whether anything had happened — the same failure as a silent diagnostic, where absence
     * reads as "nothing to report". Chat is where a player is actually looking.
     */
    private static void say(String msg) {
        System.out.println("[MIA Maps] " + msg);
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

    public static void startImport() { start(true); }
    public static void startExport() { start(false); }

    private static void start(boolean importing) {
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

        Thread t = new Thread(() -> {
            try {
                if (importing) runImport(handle, engine, px, py, pz, sector);
                else runExport(handle, engine, px, py, pz, sector);
            } catch (Throwable e) {
                System.err.println("[MIA Maps] transfer failed: " + e);
                e.printStackTrace();
            } finally {
                running.set(false);
            }
        }, "MIA-Store-Transfer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
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

        for (int dy = -V_RADIUS; dy <= V_RADIUS; dy++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    int vsx = sx0 + dx, vsy = sy0 + dy, vsz = sz0 + dz;
                    WorldSection s = engine.acquireIfExists(LEVEL, vsx, vsy, vsz);
                    if (s == null) continue;
                    try {
                        s.copyDataTo(raw);
                    } finally {
                        s.release();
                    }
                    found++;
                    int[] base = StoreTransfer.voxyToOurs(LEVEL, vsx, vsy, vsz);
                    if (base == null) { skipped++; continue; }   // outside the Abyss band
                    for (int oy = 0; oy < 2; oy++) {
                        for (int oz = 0; oz < 2; oz++) {
                            for (int ox = 0; ox < 2; ox++) {
                                if (!StoreTransfer.octantToOurs(raw, ox, oy, oz, reader, cells, biomes)) {
                                    continue;
                                }
                                if (LodNative.nIndex(handle, base[0] + ox, base[1] + oy,
                                        base[2] + oz, cells, biomes)) {
                                    written++;
                                }
                            }
                        }
                    }
                }
            }
        }
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
