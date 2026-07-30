package com.mia.aperture.map;

import com.mia.aperture.state.AbyssMapState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a {@link SourceFidelity} comparison around the player and reports it.
 *
 * <p>Modelled on {@link com.mia.aperture.lod.StoreTransferJob}: the parameters that need the client
 * are gathered on the client thread, the work runs on its own thread, and the outcome is said in
 * game rather than only to the log — a diagnostic nobody can see the result of is not a diagnostic.
 *
 * <p><b>Compares at four levels, not one.</b> Level 0 is a straight read from both stores, but the
 * coarse levels are where they are most likely to disagree: Voxy either has its own aggregate or the
 * map synthesises one by downsampling finer sections, while ours are folded by
 * {@code representative()} in the Rust crate. Those are different algorithms answering the same
 * question, so a comparison that only looked at level 0 would test the least interesting case and
 * report a reassuring number.
 */
public final class SourceFidelityJob {
    private SourceFidelityJob() {}

    /** Tiles each way from the player, per level. 4 gives a 9x9 block of tiles. */
    private static final int RADIUS = 4;
    /** Levels compared, finest first. */
    private static final int[] LEVELS = {0, 1, 2, 3};

    private static final AtomicBoolean running = new AtomicBoolean();
    /** Last outcome, for the settings screen. Null until something has run. */
    public static volatile String lastResult;
    private static volatile long finishedAt;
    private static final long LINGER_MS = 900;

    public static boolean busy() { return running.get(); }

    public static boolean showActivity() {
        return running.get() || System.currentTimeMillis() - finishedAt < LINGER_MS;
    }

    private static void say(String msg) {
        System.out.println("[MIA Mappy] " + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.execute(() -> mc.player.displayClientMessage(Component.literal("[Mappy] " + msg), false));
        }
    }

    /** Kick off a comparison. Returns false if one is already running or the inputs are not there. */
    public static boolean start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return false;

        long handle = com.mia.aperture.lod.LodIndexer.handle();
        if (handle == 0) {
            say("Source comparison: the mia-loddy store is not open, so there is nothing to compare.");
            return false;
        }
        var engine = MapEngineSource.get();
        if (engine == null) {
            say("Source comparison: Voxy has no world engine, so there is nothing to compare against.");
            return false;
        }
        // Both colour sources have to be built here, on the render thread: the Voxy bake reads the
        // mapper and the store bake needs the client's model shaper. Neither can be done on a worker.
        VoxyColorSource voxyColors = MapCompositor.colorSource();
        com.mia.aperture.lod.LodColorSource storeColors = com.mia.aperture.lod.LodColors.get();
        if (voxyColors == null || storeColors == null) {
            say("Source comparison: colours are not baked yet — open the map once, then retry.");
            return false;
        }
        if (!running.compareAndSet(false, true)) return false;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        // MapGeometry's own mirror, not Voxy's AbyssUtil. This file exists to measure whether the
        // Voxy dependency can be dropped, so reaching into Voxy for the sector would be a small
        // contradiction — and MapGeometry.sectorForX is the version that survives the drop anyway.
        int sector = MapGeometry.sectorForX(px);
        int refY = (int) Math.floor(py);
        int bandTop = AbyssMapState.mapBandTopShifted(refY, sector, false, 0);
        MapMode mode = AbyssMapState.mapRenderMode;

        Thread t = new Thread(() -> {
            try {
                run(engine, handle, voxyColors, storeColors, px, pz, sector, refY, bandTop, mode);
            } catch (Throwable e) {
                say("Source comparison failed: " + e);
                e.printStackTrace();
            } finally {
                running.set(false);
                finishedAt = System.currentTimeMillis();
            }
        }, "MIA-Source-Fidelity");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
        return true;
    }

    private static void run(me.cortex.voxy.common.world.WorldEngine engine, long handle,
                            VoxyColorSource voxyColors,
                            com.mia.aperture.lod.LodColorSource storeColors,
                            double px, double pz, int sector, int refY, int bandTop, MapMode mode) {
        say("Source comparison started (Voxy vs mia-loddy store), levels 0-3 around you...");
        long began = System.currentTimeMillis();

        // Our own store reader and our own scratch. Both the map worker's LodTileSource and its
        // straddle buffers are caller-owned mutable state, so sharing either with the running map
        // would corrupt tiles in a way that looks like a rendering bug rather than a race.
        var store = new com.mia.aperture.lod.LodTileSource(handle);
        long[] straddleLower = new long[32 * 32 * 32];
        long[] straddleUpper = new long[32 * 32 * 32];
        long[] scratch = new long[32 * 32 * 32];

        StringBuilder chatLine = new StringBuilder();
        SourceFidelity.Tally overall = new SourceFidelity.Tally();

        for (int lvl : LEVELS) {
            SourceFidelity.Tally tally = new SourceFidelity.Tally();
            int cellSize = 1 << lvl;
            int sectionSpanY = 32 * cellSize;
            int tileBlocks = 32 * cellSize;
            int centerSx = Math.floorDiv(MapGeometry.shiftX((int) Math.floor(px), sector), tileBlocks);
            int centerSz = Math.floorDiv((int) Math.floor(pz), tileBlocks);

            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    int sx = centerSx + dx, sz = centerSz + dz;
                    // engine != null selects the Voxy path, engine == null the store path.
                    int[][] v = render(lvl, sx, sz, sectionSpanY, cellSize, bandTop, refY, mode,
                            voxyColors, engine, store, sector, scratch, straddleLower, straddleUpper);
                    int[][] s = render(lvl, sx, sz, sectionSpanY, cellSize, bandTop, refY, mode,
                            storeColors, null, store, sector, scratch, straddleLower, straddleUpper);
                    tally.tile(v == null ? null : v[0], v == null ? null : v[1],
                            s == null ? null : s[0], s == null ? null : s[1]);
                    overall.tile(v == null ? null : v[0], v == null ? null : v[1],
                            s == null ? null : s[0], s == null ? null : s[1]);
                }
            }
            SourceFidelity.Result r = tally.result();
            // println, and only println. Minecraft wraps stdout in a stream that forwards on a
            // newline-terminated write; printf and print are swallowed whole. That is written down
            // in the project notes and I lost the entire per-level breakdown of the first real run
            // to it anyway, which is worth a comment rather than a second rediscovery.
            System.out.println(String.format("[MIA Mappy] fidelity level %d (%d-block cells): %s",
                    lvl, cellSize, r.summary()));
            chatLine.append(String.format(" L%d %.0f%%", lvl, r.agreement() * 100.0));
        }

        SourceFidelity.Result r = overall.result();
        lastResult = String.format("coverage %.1f%%, agreement %.1f%%",
                r.storeCoverage() * 100.0, r.agreement() * 100.0);
        System.out.println("[MIA Mappy] fidelity overall: " + r.summary());
        // Enough in chat to stand on its own. The first run reported two percentages and nothing
        // else, and neither said how much was actually compared or where a disagreement came from —
        // so a suspiciously fast run could not be told from a thorough one.
        say(String.format("Source comparison done in %.1fs over %d tiles (%d had both sources).",
                (System.currentTimeMillis() - began) / 1000.0, r.tilesCompared(),
                r.tilesBothPresent()));
        say(String.format("Store coverage %.1f%% of what Voxy draws (%d pixels missing, %d spare).",
                r.storeCoverage() * 100.0, r.pixelsVoxyOnly(), r.pixelsStoreOnly()));
        say(String.format("Agreement %.1f%% (exact %d, shade %d, differ %d) —%s",
                r.agreement() * 100.0, r.pixelsMatch(), r.pixelsShade(), r.pixelsDiffer(),
                chatLine));
        // The colour/height split, which is what says WHERE a disagreement comes from: heights
        // matching while colours do not indicts the colour bake, not the stored terrain.
        say(String.format("Heights: same %d, within 4 %d, beyond %d (worst %d).",
                r.heightSame(), r.heightClose(), r.heightFar(), r.heightWorst()));
    }

    /**
     * Render one tile from one source, returning {colors, heights}, or null if that source had no
     * data for any section of the stack.
     *
     * <p>Deliberately mirrors {@code MapWorker.renderJob} rather than calling it: the worker caches by
     * TileKey and publishes into the map, and a comparison must not disturb either. The cost is that
     * the two can drift apart — noted here because that is the maintenance risk this file carries.
     */
    static int[][] render(int lvl, int sx, int sz, int sectionSpanY, int cellSize,
                          int bandTop, int refY, MapMode mode, MapColorSource colors,
                          me.cortex.voxy.common.world.WorldEngine engine,
                          com.mia.aperture.lod.LodTileSource store,
                          int sector, long[] scratch, long[] lower, long[] upper) {
        boolean fromStore = engine == null;
        boolean caves = mode == MapMode.CAVES;
        int bandBottom = bandTop - AbyssMapState.bandHeight();
        int fetchBottom = caves ? Math.max(bandBottom, bandTop - CaveShading.SLICE_BLOCKS) : bandBottom;
        int fetchTop = caves ? Math.max(bandTop, refY + CaveShading.SLICE_BLOCKS) : bandTop;

        int topSecY = Math.floorDiv(fetchTop, sectionSpanY);
        int bottomSecY = Math.floorDiv(fetchBottom, sectionSpanY);
        int count = Math.min(12, topSecY - bottomSecY + 1);
        if (count <= 0) return null;

        long[][] sections = new long[count][];
        boolean any = false;
        for (int i = 0; i < count; i++) {
            int secY = topSecY - i;
            sections[i] = fromStore
                    ? MapWorker.acquireFromStore(store, lvl, sx, secY, sz, sector, lower, upper)
                    : MapWorker.acquireFinest(engine, lvl, sx, secY, sz, scratch, colors::isOpaque);
            if (sections[i] != null) any = true;
        }
        if (!any) return null;

        int stackBaseY = (topSecY - count + 1) * sectionSpanY;
        int topSectionTopY = (topSecY + 1) * sectionSpanY;
        int[] out = new int[32 * 32];
        int[] heights = new int[32 * 32];
        MapTileRenderer.renderTile(sections, topSectionTopY, bandTop, stackBaseY, cellSize, refY,
                mode, colors, out, heights);
        return new int[][]{out, heights};
    }
}
