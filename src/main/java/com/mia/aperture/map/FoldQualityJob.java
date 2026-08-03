package com.mia.aperture.map;

import com.mia.aperture.state.AbyssMapState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a {@link FoldQuality} comparison around the player: for each coarse level, how close does each
 * source's folded answer come to what the terrain actually looks like at one-block cells.
 *
 * <p>The follow-on from {@link SourceFidelityJob}, which established that the two sources hold the
 * same terrain and disagree only about coarse representation. This one says which representation is
 * better, which is the question that actually decides whether to default to the store.
 */
public final class FoldQualityJob {
    private FoldQualityJob() {}

    /**
     * Coarse tiles each way. Smaller than the fidelity job's radius on purpose: every coarse tile
     * here needs the whole area under it rendered again at level 0, from both sources, which is
     * 2^level squared times the work per level.
     */
    private static final int RADIUS = 2;
    private static final int[] LEVELS = {1, 2, 3};

    private static final AtomicBoolean running = new AtomicBoolean();
    public static volatile String lastResult;
    private static volatile long finishedAt;

    public static boolean busy() { return running.get(); }

    public static boolean showActivity() {
        return running.get() || System.currentTimeMillis() - finishedAt < 900;
    }

    private static void say(String msg) {
        System.out.println("[MIA Mappy] " + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.execute(() -> mc.player.displayClientMessage(Component.literal("[Mappy] " + msg), false));
        }
    }

    public static boolean start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return false;
        long handle = com.mia.aperture.lod.LodIndexer.handle();
        if (handle == 0) {
            say("Fold check: the LOD store is not open.");
            return false;
        }
        var engine = MapEngineSource.get();
        if (engine == null) {
            say("Fold check: Voxy has no world engine to compare against.");
            return false;
        }
        VoxyColorSource voxyColors = MapCompositor.colorSource();
        com.mia.aperture.lod.LodColorSource storeColors = com.mia.aperture.lod.LodColors.get();
        if (voxyColors == null || storeColors == null) {
            say("Fold check: colours are not baked yet — open the map once, then retry.");
            return false;
        }
        if (!running.compareAndSet(false, true)) return false;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int sector = MapGeometry.sectorForX(px);
        int refY = (int) Math.floor(py);
        int bandTop = AbyssMapState.mapBandTopShifted(refY, sector, false, 0);
        MapMode mode = AbyssMapState.mapRenderMode;

        Thread t = new Thread(() -> {
            try {
                run(engine, handle, voxyColors, storeColors, px, pz, sector, refY, bandTop, mode);
            } catch (Throwable e) {
                say("Fold check failed: " + e);
                e.printStackTrace();
            } finally {
                running.set(false);
                finishedAt = System.currentTimeMillis();
            }
        }, "MIA-Fold-Quality");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
        return true;
    }

    private static void run(me.cortex.voxy.common.world.WorldEngine engine, long handle,
                            VoxyColorSource voxyColors,
                            com.mia.aperture.lod.LodColorSource storeColors,
                            double px, double pz, int sector, int refY, int bandTop, MapMode mode) {
        say("Fold check started: scoring each source's coarse cells against full detail...");
        long began = System.currentTimeMillis();

        // Our own reader and scratch, for the same reason the fidelity job has its own: both are
        // caller-owned mutable buffers and the map is running.
        var store = new com.mia.aperture.lod.LodTileSource(handle);
        long[] lower = new long[32 * 32 * 32];
        long[] upper = new long[32 * 32 * 32];
        long[] scratch = new long[32 * 32 * 32];

        FoldQuality.Tally overall = new FoldQuality.Tally();
        StringBuilder chat = new StringBuilder();

        for (int lvl : LEVELS) {
            FoldQuality.Tally tally = new FoldQuality.Tally();
            int span = 1 << lvl;                       // fine tiles per coarse tile, per axis
            int coarseCell = 1 << lvl;
            int coarseSpanY = 32 * coarseCell;
            int coarseBlocks = 32 * coarseCell;
            int centerSx = Math.floorDiv(MapGeometry.shiftX((int) Math.floor(px), sector), coarseBlocks);
            int centerSz = Math.floorDiv((int) Math.floor(pz), coarseBlocks);
            int fineW = 32 * span;

            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    int sx = centerSx + dx, sz = centerSz + dz;
                    int[][] coarseStore = SourceFidelityJob.render(lvl, sx, sz, coarseSpanY,
                            coarseCell, bandTop, refY, mode, storeColors, null, store, sector,
                            scratch, lower, upper);
                    int[][] coarseVoxy = SourceFidelityJob.render(lvl, sx, sz, coarseSpanY,
                            coarseCell, bandTop, refY, mode, voxyColors, engine, store, sector,
                            scratch, lower, upper);
                    if (coarseStore == null || coarseVoxy == null) continue;

                    // The same ground, at one-block cells, from both sources.
                    int[] fineStore = new int[fineW * fineW];
                    int[] fineVoxy = new int[fineW * fineW];
                    boolean anyFine = false;
                    for (int fz = 0; fz < span; fz++) {
                        for (int fx = 0; fx < span; fx++) {
                            int fsx = sx * span + fx, fsz = sz * span + fz;
                            int[][] fs = SourceFidelityJob.render(0, fsx, fsz, 32, 1, bandTop, refY,
                                    mode, storeColors, null, store, sector, scratch, lower, upper);
                            int[][] fv = SourceFidelityJob.render(0, fsx, fsz, 32, 1, bandTop, refY,
                                    mode, voxyColors, engine, store, sector, scratch, lower, upper);
                            if (fs == null || fv == null) continue;
                            anyFine = true;
                            blit(fs[0], fineStore, fineW, fx * 32, fz * 32);
                            blit(fv[0], fineVoxy, fineW, fx * 32, fz * 32);
                        }
                    }
                    if (!anyFine) continue;

                    for (int cy = 0; cy < 32; cy++) {
                        for (int cx = 0; cx < 32; cx++) {
                            int x0 = cx * span, y0 = cy * span;
                            boolean agreed = FoldQuality.squareAgrees(fineStore, fineVoxy, fineW,
                                    x0, y0, span);
                            int truth = FoldQuality.meanColor(fineStore, fineW, x0, y0, span);
                            int i = cy * 32 + cx;
                            tally.pixel(truth, agreed, coarseStore[0][i], coarseVoxy[0][i]);
                            overall.pixel(truth, agreed, coarseStore[0][i], coarseVoxy[0][i]);
                        }
                    }
                }
            }
            FoldQuality.Verdict v = tally.verdict();
            System.out.println(String.format("[MIA Mappy] fold level %d (%d-block cells): %s",
                    lvl, coarseCell, v.summary()));
            chat.append(String.format(" L%d %.0f%%", lvl, v.storeWinShare() * 100.0));
        }

        FoldQuality.Verdict v = overall.verdict();
        System.out.println("[MIA Mappy] fold overall: " + v.summary());
        lastResult = String.format("store %.0f%% of decided", v.storeWinShare() * 100.0);
        say(String.format("Fold check done in %.1fs over %d coarse pixels.",
                (System.currentTimeMillis() - began) / 1000.0, v.scored()));
        say(String.format("Closer to full detail: store %d, Voxy %d, tie %d (store wins %.1f%% of "
                        + "decided) —%s", v.storeCloser(), v.voxyCloser(), v.tie(),
                v.storeWinShare() * 100.0, chat));
        say(String.format("Mean distance from full detail: store %.1f, Voxy %.1f.",
                v.storeMeanDistance(), v.voxyMeanDistance()));
        say("Verdict: " + v.verdict());
    }

    /** Copy a 32x32 fine tile into its place in the assembled fine grid. */
    private static void blit(int[] tile, int[] into, int intoW, int x0, int y0) {
        for (int y = 0; y < 32; y++) {
            System.arraycopy(tile, y * 32, into, (y0 + y) * intoW + x0, 32);
        }
    }
}
