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

            // Colour, resolved exactly as the live map resolves it — same bake, same tint resolver —
            // so a difference in output would be a difference in DATA, not in interpretation.
            BlockColorBake bake = new BlockColorBake();
            bake.update(table.size(), table::stateFor);
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
                    cellSize, MapMode.RELIEF, colors, colorOut, heightOut);

            int painted = 0;
            long rgbSum = 0;
            for (int c : colorOut) {
                if ((c >>> 24) != 0) {
                    painted++;
                    rgbSum += (c & 0xFFFFFF);
                }
            }
            System.out.printf("[MIA Maps] LOD tile preview @ chunk(%d,%d) y=%d: %d/%d sections, "
                            + "%d/1024 cells painted%s%n",
                    pos.getX() >> 4, pos.getZ() >> 4, pos.getY(), present, STACK, painted,
                    painted > 0 ? String.format(", mean colour #%06X", (int) (rgbSum / painted)) : "");
        } catch (Throwable t) {
            System.out.println("[MIA Maps] LOD tile preview failed: " + t);
            t.printStackTrace();
        }
    }
}
