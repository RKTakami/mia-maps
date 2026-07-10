package com.mia.aperture.map;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

public final class MapWorker {
    public static final MapTileCache CACHE = new MapTileCache(1024);
    public static final java.util.concurrent.atomic.AtomicInteger COMPLETED = new java.util.concurrent.atomic.AtomicInteger();

    // Unbounded deque is acceptable: PENDING dedupe caps growth at the number of distinct
    // visible tiles; addFirst gives newest requests priority
    private static final LinkedBlockingDeque<Job> QUEUE = new LinkedBlockingDeque<>();
    private static final Set<TileKey> PENDING = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static volatile Thread thread;

    private record Job(TileKey key, int bandTopY, int bandBottomY,
                       WorldEngine engine, MapColorSource colors, int generation) {}

    private MapWorker() {}

    // Called from the render thread. Returns the cached tile (possibly stale) or null,
    // enqueueing a render when missing or expired.
    // Band Y values are captured by the FIRST request for a key; coalesced duplicates may
    // differ by up to the 16-block band quantum — accepted by design
    public static MapTile request(TileKey key, int bandTopY, int bandBottomY,
                                  WorldEngine engine, MapColorSource colors, long maxAgeMs) {
        MapTile tile = CACHE.get(key);
        boolean fresh = tile != null
                && (maxAgeMs <= 0 || System.currentTimeMillis() - tile.renderedAtMs() < maxAgeMs);
        if (!fresh && PENDING.add(key)) {
            ensureThread();
            QUEUE.addFirst(new Job(key, bandTopY, bandBottomY, engine, colors, GENERATION.get()));
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

    public static int queueSize() {
        return QUEUE.size();
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

    private static void renderJob(Job job, long[] scratch) {
        TileKey key = job.key();
        int lvl = key.lvl();
        int cellSize = 1 << lvl;
        int sectionSpanY = 32 * cellSize;

        int topSecY = Math.floorDiv(job.bandTopY(), sectionSpanY);
        int bottomSecY = Math.floorDiv(job.bandBottomY(), sectionSpanY);
        int count = Math.min(12, topSecY - bottomSecY + 1);

        long[][] sections = new long[count][];
        for (int i = 0; i < count; i++) {
            int secY = topSecY - i;
            WorldSection section = job.engine().acquireIfExists(lvl, key.sx(), secY, key.sz());
            if (section == null) continue;
            try {
                section.copyDataTo(scratch);
                sections[i] = scratch.clone();
            } finally {
                section.release();
            }
        }

        int stackBaseY = (topSecY - count + 1) * sectionSpanY;
        int topSectionTopY = (topSecY + 1) * sectionSpanY;
        int[] colors = new int[32 * 32];
        int[] heights = new int[32 * 32];
        MapTileRenderer.renderTile(sections, topSectionTopY, job.bandTopY(), stackBaseY,
                cellSize, key.mode(), job.colors(), colors, heights);
        if (job.generation() == GENERATION.get()) {
            CACHE.put(key, new MapTile(colors, heights, System.currentTimeMillis()));
            COMPLETED.incrementAndGet();
        }
    }
}
