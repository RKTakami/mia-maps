package com.mia.aperture.lod;

import com.mia.aperture.map.BiomeTintResolver;
import com.mia.aperture.map.BlockColorBake;
import com.mia.aperture.map.MapMode;
import com.mia.aperture.map.MapTileRenderer;
import net.minecraft.client.Minecraft;

/**
 * Renders one map tile from the LOD store and reports what came out.
 *
 * <p>Proves the whole read chain end to end — store, block resolution, biome tint, colour bake and
 * the map's own tile renderer — <b>without</b> touching what the map actually draws. The store is
 * still being introduced alongside the existing path, so nothing here can regress the live map; if
 * this produces nonsense, the map is unaffected and we know before wiring anything.
 */
public final class LodTilePreview {
    private LodTilePreview() {}

    /** Sections stacked for the preview: 64 blocks of depth at level 0, enough to contain a surface. */
    private static final int STACK = 2;

    public static void run(long handle, LodBlockTable table) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.player == null) {
                System.out.println("[MIA Maps] LOD tile preview skipped: no level");
                return;
            }

            // Announce on entry. A previous version printed nothing at all and the silence was
            // ambiguous between "did not run", "failed" and "still running" — the worst outcome for
            // a diagnostic.
            System.out.println("[MIA Maps] LOD tile preview: baking " + table.size() + " states...");

            // Colour, resolved exactly as the live map resolves it — same bake, same tint resolver —
            // so a difference in output would be a difference in DATA, not in interpretation.
            //
            // NOTE this is expensive and runs on the client thread, because baking needs
            // BlockModelShaper. The live map spreads the same work out, baking ids as they appear;
            // doing thousands at once is a diagnostic-only cost and is timed so it cannot hide.
            long t0 = System.currentTimeMillis();
            BlockColorBake bake = new BlockColorBake();
            bake.update(table.size(), table::stateFor);
            System.out.println("[MIA Maps] LOD tile preview: bake took "
                    + (System.currentTimeMillis() - t0) + "ms");
            BiomeTintResolver tints = new BiomeTintResolver(
                    id -> {
                        String key = LodNative.nBlockKey(handle, id);
                        return key != null && key.startsWith(LodNative.BIOME_PREFIX)
                                ? key.substring(LodNative.BIOME_PREFIX.length()) : null;
                    },
                    mc.level);
            LodColorSource colors = new LodColorSource(bake.snapshot(), tints);

            var pos = mc.player.blockPosition();
            int sx = Math.floorDiv(pos.getX() >> 4, 2);
            int sz = Math.floorDiv(pos.getZ() >> 4, 2);
            int topSectionY = Math.floorDiv(pos.getY() >> 4, 2);

            LodTileSource src = new LodTileSource(handle);
            long[][] sections = new long[STACK][];
            int present = 0;
            for (int i = 0; i < STACK; i++) {
                long[] sec = new long[LodTileSource.BIG_CELLS];
                // Top-to-bottom, as the renderer expects.
                if (src.buildSection(0, sx, topSectionY - i, sz, sec)) {
                    sections[i] = sec;
                    present++;
                }
            }

            int cellSize = 1;                       // level 0
            int topSectionTopY = (topSectionY + 1) * 32;
            int stackBaseY = topSectionTopY - STACK * 32 * cellSize;
            int[] colorOut = new int[32 * 32];
            int[] heightOut = new int[32 * 32];
            MapTileRenderer.renderTile(sections, topSectionTopY, topSectionTopY, stackBaseY,
                    cellSize, topSectionTopY, MapMode.RELIEF, colors, colorOut, heightOut);

            int painted = 0;
            long rgbSum = 0;
            for (int c : colorOut) {
                if ((c >>> 24) != 0) {
                    painted++;
                    rgbSum += (c & 0xFFFFFF);
                }
            }
            // println, NOT printf: Minecraft's stdout swallows printf because it never flushes.
            // Documented in this project's notes, and this diagnostic lost its only result line to
            // it — the two println lines around it printed fine.
            System.out.println("[MIA Maps] LOD tile preview @ chunk("
                    + (pos.getX() >> 4) + "," + (pos.getZ() >> 4) + ") y=" + pos.getY()
                    + ": " + present + "/" + STACK + " sections, "
                    + painted + "/1024 cells painted"
                    + (painted > 0
                       ? ", mean colour #" + String.format("%06X", (int) (rgbSum / painted))
                       : ""));
        } catch (Throwable t) {
            System.out.println("[MIA Maps] LOD tile preview failed: " + t);
            t.printStackTrace();
        }
    }
}
