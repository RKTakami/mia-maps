package com.mia.aperture.lod;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Captures loaded chunks into the LOD store.
 *
 * <p><b>All database work happens on a daemon worker</b>, never the client thread. The client thread
 * only clones a chunk's section-reference array — a couple of dozen pointers — and enqueues it.
 * Interning goes through JNI to a database, and a section is 4096 cells; doing that inline would
 * hitch the game every time a chunk loaded.
 *
 * <p>Reading sections off the client thread can race a concurrent block update, so a cell may be
 * captured a tick stale. For a map that is the right trade: the alternative is doing the work inline
 * and stuttering, and the chunk is re-indexed next time it loads anyway.
 *
 * <p>Runs <b>alongside</b> the existing data path and writes nothing the map reads yet, so it cannot
 * regress anything while it is being proven.
 */
public final class LodIndexer {
    /** Bounded so a worker that falls behind costs dropped work rather than memory. Dropped chunks
     *  are re-indexed the next time they load, so the cost is delay, not loss. */
    private static final int QUEUE_CAPACITY = 512;
    private static final long FLUSH_INTERVAL_MS = 2000;

    private record Job(int chunkX, int chunkZ, int minSectionY, LevelChunkSection[] sections) {}

    private static final BlockingQueue<Job> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final AtomicLong dropped = new AtomicLong();
    private static final AtomicLong indexed = new AtomicLong();

    /** Resolved on join, so a session reports immediately whether stored ids still mean anything. */
    private static final LodBlockTable BLOCK_TABLE = new LodBlockTable();

    private static volatile long handle;
    private static volatile boolean running;
    /** Published by the worker so close() can report what it saw. */
    private static volatile BlockIdCache workerCache;
    /** One-shot cross-check, run while the world still exists. */
    private static volatile long crossCheckAt;
    private static Thread worker;

    private LodIndexer() {}

    public static boolean active() { return running && handle != 0; }

    public static long sectionsIndexed() { return indexed.get(); }

    public static long sectionsDropped() { return dropped.get(); }

    /** Stored ids resolved back to live block states. The read path depends on this. */
    public static LodBlockTable blockTable() { return BLOCK_TABLE; }

    public static long handle() { return handle; }

    /**
     * Open the store for one world and start indexing.
     *
     * <p>The store is per world key, so terrain from different servers never mixes — the same
     * coordinates mean different places.
     */
    public static synchronized void open(Path gameDir, String worldKey) {
        if (running) return;
        LodNative.ensureLoaded();
        if (!LodNative.available()) return;
        try {
            // New installs use .mia-loddy; an existing .mia-lods keeps being used. Renaming the
            // directory outright would orphan every section already captured — half a million on
            // this machine — for a cosmetic change, and a silent migration of a live database is
            // not worth the risk when keeping the old path costs one branch.
            Path dir = gameDir.resolve(".mia-loddy");
            Path legacy = gameDir.resolve(".mia-lods");
            if (!Files.isDirectory(dir) && Files.isDirectory(legacy)) dir = legacy;
            Files.createDirectories(dir);
            Path db = dir.resolve(worldKey + ".redb");
            long h = LodNative.nOpen(db.toString());
            if (h == 0) {
                System.err.println("[MIA Maps] LOD store failed to open at " + db);
                return;
            }
            handle = h;
            running = true;
            QUEUE.clear();
            worker = new Thread(LodIndexer::run, "MIA-LOD-Indexer");
            worker.setDaemon(true);
            worker.start();
            System.out.println("[MIA Maps] LOD store open: " + db);
            // Deliberately delayed rather than run here: at join the player is not yet placed, and
            // at disconnect the level is already gone — an earlier attempt ran then and printed
            // nothing at all, which is worse than a wrong answer.
            crossCheckAt = System.currentTimeMillis() + 8000;
            // Resolve now rather than lazily: if stored ids no longer mean anything in this game,
            // that is worth knowing at join, not the first time the map tries to draw from them.
            BLOCK_TABLE.resolve(h);
            System.out.println("[MIA Maps] LOD blocks resolved=" + BLOCK_TABLE.resolvedCount()
                    + " unresolved=" + BLOCK_TABLE.unresolvedCount()
                    + " sections=" + LodNative.nLen(h));
        } catch (Throwable t) {
            System.err.println("[MIA Maps] LOD store open failed: " + t);
        }
    }

    /** Stop indexing, flush what is pending, and close. Safe to call when not open. */
    public static synchronized void close() {
        if (!running) return;
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            w.interrupt();
            try {
                // Bounded: a flush of a large dirty set can take a moment, but the game should not
                // hang on shutdown if the worker is wedged.
                w.join(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        long h = handle;
        handle = 0;
        long skippedAtClose = -1;
        if (h != 0) {
            skippedAtClose = LodNative.nSkipped(h);
            // Fold whatever is outstanding before closing, so the pyramid is current next session
            // rather than carrying work forward.
            LodNative.nFlush(h);
            LodNative.nClose(h);
        }
        LodColors.reset();
        LodWorldRenderer.reset();
        BlockIdCache c = workerCache;
        String biomeInfo = "";
        if (c != null) {
            java.util.List<String> names = c.biomeNames();
            biomeInfo = " states=" + c.distinctStates() + " biomes=" + c.distinctBiomes()
                    + (names.size() <= 12 ? " " + names : " " + names.subList(0, 12) + "...");
        }
        System.out.println("[MIA Maps] LOD store closed. indexed=" + indexed.get()
                + " skipped=" + skippedAtClose + " dropped=" + dropped.get() + biomeInfo);
    }

    /**
     * Client thread. Reports what two independent sources think the biome situation is, once per
     * session, while the world is still loaded.
     *
     * <p>Three readings that agree mean the world really is uniform and our capture is right;
     * disagreement says which direction the error runs.
     */
    public static void tickCrossCheck() {
        long due = crossCheckAt;
        if (due == 0 || System.currentTimeMillis() < due) return;
        crossCheckAt = 0;
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.player == null) {
                System.out.println("[MIA Maps] biome cross-check skipped: no level");
                return;
            }
            var here = mc.level.getBiome(mc.player.blockPosition());
            System.out.println("[MIA Maps] biome cross-check: vanilla at player = "
                    + here.unwrapKey().map(k -> k.identifier().toString()).orElse("<unkeyed>"));

            // Sample a spread of positions, not just underfoot: one reading proves nothing about
            // whether the world varies.
            java.util.Set<String> seen = new java.util.TreeSet<>();
            var p = mc.player.blockPosition();
            for (int dx = -256; dx <= 256; dx += 64) {
                for (int dz = -256; dz <= 256; dz += 64) {
                    mc.level.getBiome(p.offset(dx, 0, dz)).unwrapKey()
                            .ifPresent(k -> seen.add(k.identifier().toString()));
                }
            }
            System.out.println("[MIA Maps] biome cross-check: vanilla across 512 blocks = " + seen);

            var engine = com.mia.aperture.map.MapEngineSource.get();
            System.out.println("[MIA Maps] biome cross-check: existing path knows "
                    + (engine == null ? "<no engine>"
                       : String.valueOf(engine.getMapper().getBiomeEntries().length)) + " biomes");

            // End-to-end read check: store to rendered tile, using the map's own renderer.
            long h = handle;
            if (h != 0) LodTilePreview.run(h, BLOCK_TABLE);
        } catch (Throwable t) {
            System.out.println("[MIA Maps] biome cross-check failed: " + t);
        }
    }

    /**
     * Client thread. Enqueue a loaded chunk; does no database work and allocates almost nothing.
     */
    public static void onChunkLoad(LevelChunk chunk) {
        if (!active() || chunk == null) return;
        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) return;
        Job job = new Job(chunk.getPos().x, chunk.getPos().z,
                chunk.getMinSectionY(), sections.clone());
        if (!QUEUE.offer(job)) {
            dropped.incrementAndGet();
        }
    }

    private static void run() {
        BlockIdCache cache = new BlockIdCache(handle);
        workerCache = cache;
        int[] cells = new int[LodNative.CELLS];
        int[] biomes = new int[LodNative.BIOME_CELLS];
        long lastFlush = System.currentTimeMillis();
        while (running) {
            try {
                Job job = QUEUE.poll(250, TimeUnit.MILLISECONDS);
                if (job != null) {
                    long h = handle;
                    if (h == 0) break;
                    for (int i = 0; i < job.sections.length; i++) {
                        LevelChunkSection sec = job.sections[i];
                        if (!SectionCapture.capture(sec, cache, cells, biomes)) continue;
                        if (LodNative.nIndex(h, job.chunkX, job.minSectionY + i, job.chunkZ, cells, biomes)) {
                            indexed.incrementAndGet();
                        }
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    long h = handle;
                    // Folding is deferred by design; without this the coarse levels never appear.
                    if (h != 0) LodNative.nFlush(h);
                    lastFlush = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // One bad chunk must not kill indexing for the session.
                System.err.println("[MIA Maps] LOD indexing error: " + t);
            }
        }
    }
}
