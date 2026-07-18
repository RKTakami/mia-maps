package com.mia.aperture.map;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

// Background builder for AbyssSpanStore: probes every LOD-4 section in the shifted column once
// (rate-limited so the game never hitches), converts solid cells to column spans, and publishes
// immutable snapshots as it goes — the whole-Abyss view fills in progressively. Sections near the
// player are re-probed on a slow cadence so newly explored terrain appears in the overview. All
// Voxy contact for the whole-Abyss feature is quarantined here.
public final class AbyssModelBuilder {
    private static final int LVL = 4;                    // native 16-block LOD — never synthesize
    private static final int SEC_CELLS = 32;             // cells per section edge at LVL
    private static final int SEC_BLOCKS = SEC_CELLS << LVL;
    private static final int SEC_XZ_MIN = -16, SEC_XZ_MAX = 15;   // [-8192, 8192) blocks
    private static final int SEC_Y_MIN = -8, SEC_Y_MAX = 8;       // covers the Abyss band
    private static final int BATCH = 64;                 // probes per loop iteration
    private static final long SLEEP_MS = 10;
    private static final int PUBLISH_EVERY = 512;        // sections between progressive publishes
    private static final long DIRTY_PERIOD_MS = 10_000;
    private static final int DIRTY_RADIUS_BLOCKS = 768;
    private static final int MAX_COLUMNS = 2_000_000;    // defends against a coordinate bug

    private static final Map<Integer, AbyssSpanStore.Column> working = new HashMap<>();
    private static final ArrayDeque<long[]> queue = new ArrayDeque<>();
    private static int probed, total;
    private static long lastDirtyMs;
    private static boolean overflowLogged;
    private static Thread thread;

    private AbyssModelBuilder() {}

    public static synchronized void ensureStarted() {
        if (thread != null && thread.isAlive()) return;
        for (int sy = SEC_Y_MIN; sy <= SEC_Y_MAX; sy++) {
            for (int sz = SEC_XZ_MIN; sz <= SEC_XZ_MAX; sz++) {
                for (int sx = SEC_XZ_MIN; sx <= SEC_XZ_MAX; sx++) {
                    queue.add(new long[]{sx, sy, sz});
                }
            }
        }
        total = queue.size();
        probed = 0;
        thread = new Thread(AbyssModelBuilder::loop, "MIA-Abyss-Model");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void loop() {
        boolean[] opaque = new boolean[SEC_CELLS * SEC_CELLS * SEC_CELLS];
        int[] argb = new int[SEC_CELLS * SEC_CELLS * SEC_CELLS];
        long[] scratch = new long[SEC_CELLS * SEC_CELLS * SEC_CELLS];
        long[][] synth = new long[1][];
        while (true) {
            try {
                VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
                MapColorSource colors = MapCompositor.colorSource();
                if (rs == null || colors == null) { Thread.sleep(500); continue; }
                WorldEngine engine = rs.getEngine();

                int did = 0;
                long[] sec;
                while (did < BATCH && (sec = queue.poll()) != null) {
                    probeSection(engine, colors, (int) sec[0], (int) sec[1], (int) sec[2],
                            opaque, argb, scratch, synth);
                    probed++;
                    did++;
                    if (probed % PUBLISH_EVERY == 0) {
                        AbyssSpanStore.publish(AbyssSpanStore.buildSnapshot(working, probed, total));
                    }
                }
                if (did > 0 && queue.isEmpty()) {
                    AbyssSpanStore.publish(AbyssSpanStore.buildSnapshot(working, probed, total));
                }
                if (queue.isEmpty()) enqueueDirtyNearPlayer();
                Thread.sleep(queue.isEmpty() ? 250 : SLEEP_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                System.err.println("[MIA Maps] abyss model build failed: " + t);
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }
    }

    // One LOD-4 section -> spans. Clears the section's Y window first so a re-probe replaces stale
    // data instead of unioning with it (fresh terrain, removed blocks).
    private static void probeSection(WorldEngine engine, MapColorSource colors,
            int secX, int secY, int secZ,
            boolean[] opaque, int[] argb, long[] scratch, long[][] synth) {
        Arrays.fill(opaque, false);
        VoxelCloud.fillInto(engine, colors, secX * SEC_CELLS, secY * SEC_CELLS, secZ * SEC_CELLS,
                SEC_CELLS, SEC_CELLS, SEC_CELLS, LVL, opaque, argb, scratch, synth);
        int yTop = secY * SEC_CELLS + SEC_CELLS - 1, yBottom = secY * SEC_CELLS;
        for (int lz = 0; lz < SEC_CELLS; lz++) {
            for (int lx = 0; lx < SEC_CELLS; lx++) {
                int key = AbyssSpanStore.packKey(secX * SEC_CELLS + lx, secZ * SEC_CELLS + lz);
                AbyssSpanStore.Column col = SpanMath.clearRange(working.get(key), yTop, yBottom);
                int runTop = -1;
                for (int ly = SEC_CELLS - 1; ly >= 0; ly--) {
                    boolean solid = opaque[(ly * SEC_CELLS + lz) * SEC_CELLS + lx];
                    if (solid && runTop < 0) runTop = ly;
                    if ((!solid || ly == 0) && runTop >= 0) {
                        int runBottom = solid ? ly : ly + 1;
                        col = SpanMath.insertRun(col, secY * SEC_CELLS + runTop, secY * SEC_CELLS + runBottom,
                                argb[(runTop * SEC_CELLS + lz) * SEC_CELLS + lx]);
                        runTop = -1;
                    }
                }
                if (col == null) working.remove(key);
                else if (working.size() < MAX_COLUMNS || working.containsKey(key)) working.put(key, col);
                else if (!overflowLogged) {
                    overflowLogged = true;
                    System.err.println("[MIA Maps] abyss model column cap hit — coordinate bug?");
                }
            }
        }
    }

    // Newly explored terrain: re-probe sections near the player every DIRTY_PERIOD_MS. Polling is
    // enough for an overview; there is no event hook into Voxy ingestion.
    private static void enqueueDirtyNearPlayer() {
        long now = System.currentTimeMillis();
        if (now - lastDirtyMs < DIRTY_PERIOD_MS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        lastDirtyMs = now;
        double[] p = MapGeometry.toShiftedColumn(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        int r = DIRTY_RADIUS_BLOCKS;
        int sx0 = Math.floorDiv((int) p[0] - r, SEC_BLOCKS), sx1 = Math.floorDiv((int) p[0] + r, SEC_BLOCKS);
        int sy0 = Math.floorDiv((int) p[1] - r, SEC_BLOCKS), sy1 = Math.floorDiv((int) p[1] + r, SEC_BLOCKS);
        int sz0 = Math.floorDiv((int) p[2] - r, SEC_BLOCKS), sz1 = Math.floorDiv((int) p[2] + r, SEC_BLOCKS);
        for (int sy = Math.max(SEC_Y_MIN, sy0); sy <= Math.min(SEC_Y_MAX, sy1); sy++) {
            for (int sz = Math.max(SEC_XZ_MIN, sz0); sz <= Math.min(SEC_XZ_MAX, sz1); sz++) {
                for (int sx = Math.max(SEC_XZ_MIN, sx0); sx <= Math.min(SEC_XZ_MAX, sx1); sx++) {
                    queue.add(new long[]{sx, sy, sz});
                }
            }
        }
    }
}
