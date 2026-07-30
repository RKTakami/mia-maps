package com.mia.aperture.map;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

public final class MapWorker {
    public static final MapTileCache CACHE = new MapTileCache(4096);
    public static final java.util.concurrent.atomic.AtomicInteger COMPLETED = new java.util.concurrent.atomic.AtomicInteger();

    // Unbounded deque is acceptable: PENDING dedupe caps growth at the number of distinct
    // visible tiles; addFirst gives newest requests priority
    private static final LinkedBlockingDeque<Job> QUEUE = new LinkedBlockingDeque<>();
    private static final Set<TileKey> PENDING = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static volatile Thread thread;
    private static final int MAX_FALLBACK_K = 4;
    // How many levels FINER we'll synthesize a coarse tile from when Voxy lacks the aggregate
    // LOD for an explored region (depth 2 covers e.g. a lvl-3 view built from lvl-1 data).
    private static final int MAX_FINER_DEPTH = 2;

    private record Job(TileKey key, int bandTopY, int bandBottomY, int referenceY, int sector,
                       WorldEngine engine, MapColorSource colors, int generation) {}

    private MapWorker() {}

    // Called from the render thread. Returns the cached tile (possibly stale) or null,
    // enqueueing a render when missing or expired.
    // Band Y values are captured by the FIRST request for a key; coalesced duplicates may
    // differ by up to the 16-block band quantum — accepted by design
    public static MapTile request(TileKey key, int bandTopY, int bandBottomY, int referenceY,
                                  int sector, WorldEngine engine, MapColorSource colors,
                                  long maxAgeMs) {
        MapTile tile = CACHE.get(key);
        boolean fresh = tile != null
                && (maxAgeMs <= 0 || System.currentTimeMillis() - tile.renderedAtMs() < maxAgeMs);
        if (!fresh && PENDING.add(key)) {
            ensureThread();
            QUEUE.addFirst(new Job(key, bandTopY, bandBottomY, referenceY, sector, engine, colors, GENERATION.get()));
        }
        return tile;
    }

    public static void reset() {
        GENERATION.incrementAndGet();
        QUEUE.clear();
        PENDING.clear();
        CACHE.clear();
        COMPLETED.set(0);
    }

    // Drop any queued/in-flight tile work without clearing the cache, so closing the
    // fullscreen map stops the worker churning but a reopen stays fast.
    public static void cancelPending() {
        GENERATION.incrementAndGet();
        QUEUE.clear();
        PENDING.clear();
    }

    private static void ensureThread() {
        if (thread != null && thread.isAlive()) return;
        synchronized (MapWorker.class) {
            if (thread != null && thread.isAlive()) return;
            Thread t = new Thread(MapWorker::runLoop, "MIA-Map-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            thread = t;
            t.start();
        }
    }

    private static void runLoop() {
        long[] scratch = new long[32 * 32 * 32];
        while (true) {
            Job job;
            try {
                job = QUEUE.takeFirst();
            } catch (InterruptedException e) {
                System.err.println("[MIA Aperture] map worker interrupted, exiting");
                return;
            }
            try {
                if (job.generation() == GENERATION.get()) {
                    renderJob(job, scratch);
                }
            } catch (Throwable t) {
                System.err.println("[MIA Aperture] map tile job failed for " + job.key() + ": " + t);
            } finally {
                PENDING.remove(job.key());
            }
        }
    }

    // Return a 32^3 section for the display-level coords. Prefer this level or coarser
    // (upsampled); if neither exists, synthesize it by downsampling finer levels — Voxy
    // stores fine data for explored regions but may lack the coarse LOD aggregate, which is
    // why a zoomed-out (coarse-level) tile would otherwise come back empty. Null if no data.
    private static long[] acquireFinest(WorldEngine engine, int lvl, int sx, int secY, int sz, long[] scratch,
                                        java.util.function.LongPredicate renderable) {
        long[] direct = acquireCoarser(engine, lvl, sx, secY, sz, scratch);
        if (direct != null) return direct;
        return synthesizeFromFiner(engine, lvl, sx, secY, sz, scratch, 0, renderable);
    }

    // This display level, then progressively coarser Voxy levels (upsampled). Fresh array or null.
    private static long[] acquireCoarser(WorldEngine engine, int lvl, int sx, int secY, int sz, long[] scratch) {
        for (int k = 0; k <= MAX_FALLBACK_K; k++) {
            WorldSection cs = engine.acquireIfExists(lvl + k, sx >> k, secY >> k, sz >> k);
            if (cs == null) continue;
            try {
                cs.copyDataTo(scratch);
                return k == 0 ? scratch.clone() : LodUpsampler.upsampleOctant(scratch, sx, secY, sz, k);
            } finally {
                cs.release();
            }
        }
        return null;
    }

    // Build this coarse section from the 8 child sections one level finer (recursively, bounded),
    // downsampling each into its octant. Handles explored regions Voxy never aggregated coarse.
    private static long[] synthesizeFromFiner(WorldEngine engine, int lvl, int sx, int secY, int sz,
                                              long[] scratch, int depth,
                                              java.util.function.LongPredicate renderable) {
        if (lvl <= 0 || depth >= MAX_FINER_DEPTH) return null;
        long[] out = null;
        for (int dy = 0; dy < 2; dy++) {
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    int cx = (sx << 1) + dx, cy = (secY << 1) + dy, cz = (sz << 1) + dz;
                    long[] child = acquireCoarser(engine, lvl - 1, cx, cy, cz, scratch);
                    if (child == null) {
                        child = synthesizeFromFiner(engine, lvl - 1, cx, cy, cz, scratch, depth + 1, renderable);
                    }
                    if (child == null) continue;
                    if (out == null) out = new long[32 * 32 * 32];
                    LodUpsampler.mipInto(out, child, dx, dy, dz, renderable);
                }
            }
        }
        return out;
    }

    /** Per-thread, because buildSection fills caller-owned buffers and the worker is the only user. */
    private static com.mia.aperture.lod.LodTileSource storeCache;

    private static com.mia.aperture.lod.LodTileSource storeSource() {
        long h = com.mia.aperture.lod.LodIndexer.handle();
        if (h == 0) return null;
        com.mia.aperture.lod.LodTileSource s = storeCache;
        if (s == null) storeCache = s = new com.mia.aperture.lod.LodTileSource(h);
        return s;
    }

    /**
     * One 32-cell section read out of the store, addressed through the shifted-to-vanilla conversion.
     *
     * <p>Returns null when the store has never seen the region, which the renderer already treats as
     * missing rather than empty — so an unexplored area reads as blank instead of as solid air.
     */
    private static long[] acquireFromStore(com.mia.aperture.lod.LodTileSource store, int lvl,
                                           int sx, int secY, int sz, int sector) {
        int[] a = com.mia.aperture.lod.LodTileAddress.bigSection(lvl, sx, secY, sz, sector);
        if (a == null) return null;   // misaligned; compose should not have chosen the store at all
        long[] out = new long[32 * 32 * 32];
        return store.buildSection(lvl, a[0], a[1], a[2], out) ? out : null;
    }

    private static void renderJob(Job job, long[] scratch) {
        TileKey key = job.key();
        int lvl = key.lvl();
        int cellSize = 1 << lvl;
        int sectionSpanY = 32 * cellSize;

        // CAVES reads a slab around the PLAYER, not the whole band: SLICE_BLOCKS below for the
        // floor you are standing on, and the same above for the ledge pass. Trimming the bottom is
        // an optimisation the renderer does not depend on (it enforces its own bound), but raising
        // the top is REQUIRED — without those sections there is no data above the band top for the
        // upward pass to find, and it would silently return nothing.
        boolean caves = key.mode() == MapMode.CAVES;
        int bandBottom = caves
                ? Math.max(job.bandBottomY(), job.bandTopY() - CaveShading.SLICE_BLOCKS)
                : job.bandBottomY();
        int fetchTop = caves
                ? Math.max(job.bandTopY(), job.referenceY() + CaveShading.SLICE_BLOCKS)
                : job.bandTopY();

        int topSecY = Math.floorDiv(fetchTop, sectionSpanY);
        int bottomSecY = Math.floorDiv(bandBottom, sectionSpanY);
        int count = Math.min(12, topSecY - bottomSecY + 1);

        long[][] sections = new long[count][];
        com.mia.aperture.lod.LodTileSource store = key.fromStore() ? storeSource() : null;
        for (int i = 0; i < count; i++) {
            int secY = topSecY - i;
            if (store != null) {
                sections[i] = acquireFromStore(store, lvl, key.sx(), secY, key.sz(), job.sector());
            } else {
                sections[i] = acquireFinest(job.engine(), lvl, key.sx(), secY, key.sz(), scratch,
                        job.colors()::isOpaque);
            }
        }

        int stackBaseY = (topSecY - count + 1) * sectionSpanY;
        int topSectionTopY = (topSecY + 1) * sectionSpanY;
        int[] colors = new int[32 * 32];
        int[] heights = new int[32 * 32];
        MapTileRenderer.renderTile(sections, topSectionTopY, job.bandTopY(), stackBaseY,
                cellSize, job.referenceY(), key.mode(), job.colors(), colors, heights);
        if (job.generation() == GENERATION.get()) {
            CACHE.put(key, new MapTile(colors, heights, System.currentTimeMillis()));
            COMPLETED.incrementAndGet();
        }
    }
}
