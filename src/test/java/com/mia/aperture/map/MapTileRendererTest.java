package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapTileRendererTest {

    private static final int STONE = 0xFF808080;
    private static final int WATER = 0xFF4040FF;

    // Mapping ids in tests: 0 = air, 1 = stone, 2 = water
    private final MapColorSource colors = new MapColorSource() {
        @Override public int baseColor(long id) { return id == 1 ? STONE : id == 2 ? WATER : 0; }
        @Override public boolean isWater(long id) { return id == 2; }
        @Override public boolean isOpaque(long id) { return id == 1 || id == 2; }
    };

    private static long[] emptySection() { return new long[32 * 32 * 32]; }

    private static void fillLayer(long[] section, int cellY, long id) {
        for (int z = 0; z < 32; z++)
            for (int x = 0; x < 32; x++)
                section[(cellY << 10) | (z << 5) | x] = id;
    }

    private static int idx(int x, int z) { return z * 32 + x; }

    @Test
    void findsFlatSurfaceAndHeight() {
        long[] sec = emptySection();
        fillLayer(sec, 10, 1); // stone at cellY 10
        int[] color = new int[1024];
        int[] height = new int[1024];
        // section top face at shifted Y 320 (base 288), cellSize 1, band covers it all
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.VANILLA, colors, color, height);
        assertEquals(288 + 10, height[idx(5, 5)]);
        assertNotEquals(0, color[idx(5, 5)]);
    }

    @Test
    void emptyColumnIsTransparent() {
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{emptySection()}, 320, 320, 288, 1, MapMode.VANILLA, colors, color, height);
        assertEquals(0, color[idx(0, 0)]);
        assertEquals(Integer.MIN_VALUE, height[idx(0, 0)]);
    }

    @Test
    void bandClipsSurfacesAboveIt() {
        long[] sec = emptySection();
        fillLayer(sec, 30, 1); // high surface at 288+30=318
        fillLayer(sec, 5, 1);  // low surface at 293
        int[] color = new int[1024];
        int[] height = new int[1024];
        // band top 300: the 318 surface must be ignored, 293 found
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 300, 288, 1, MapMode.VANILLA, colors, color, height);
        assertEquals(293, height[idx(0, 0)]);
    }

    @Test
    void nullSectionsAreSkipped() {
        long[] sec = emptySection();
        fillLayer(sec, 0, 1); // block Y 288 in the SECOND section (base 256)
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{null, sec}, 352, 352, 256, 1, MapMode.VANILLA, colors, color, height);
        assertEquals(256, height[idx(0, 0)]);
    }

    @Test
    void vanillaModeStepsBrightnessBySlope() {
        long[] sec = emptySection();
        fillLayer(sec, 10, 1);
        // one column higher: at z=16 raise to 11
        for (int x = 0; x < 32; x++) sec[(11 << 10) | (16 << 5) | x] = 1;
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.VANILLA, colors, color, height);
        int flat = color[idx(5, 5)];
        int atStep = color[idx(5, 16)];      // higher than its north neighbor -> brighter
        int belowStep = color[idx(5, 17)];   // lower than its north neighbor -> darker
        assertTrue((atStep & 0xFF) > (flat & 0xFF));
        assertTrue((belowStep & 0xFF) < (flat & 0xFF));
    }

    @Test
    void reliefModeBrightensSouthFacingSlopes() {
        long[] sec = emptySection();
        for (int z = 0; z < 32; z++)
            for (int x = 0; x < 32; x++)
                sec[((z) << 10) | (z << 5) | x] = 1; // height rises with z (southward up)
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.RELIEF, colors, color, height);
        int slope = color[idx(5, 10)];
        // flat reference tile
        long[] flatSec = emptySection();
        fillLayer(flatSec, 10, 1);
        int[] flatColor = new int[1024];
        MapTileRenderer.renderTile(new long[][]{flatSec}, 320, 320, 288, 1, MapMode.RELIEF, colors, flatColor, new int[1024]);
        assertTrue((slope & 0xFF) > (flatColor[idx(5, 10)] & 0xFF));
    }

    @Test
    void waterBlendsAndDarkensWithDepth() {
        long[] sec = emptySection();
        fillLayer(sec, 20, 2);  // water surface
        fillLayer(sec, 19, 2);
        fillLayer(sec, 18, 2);
        fillLayer(sec, 17, 1);  // floor
        long[] deep = emptySection();
        fillLayer(deep, 20, 2);
        for (int y = 5; y < 20; y++) fillLayer(deep, y, 2);
        fillLayer(deep, 4, 1);
        int[] shallow = new int[1024];
        int[] deepC = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.VANILLA, colors, shallow, new int[1024]);
        MapTileRenderer.renderTile(new long[][]{deep}, 320, 320, 288, 1, MapMode.VANILLA, colors, deepC, new int[1024]);
        // deeper water is darker, both are water-ish (blue channel dominant)
        assertTrue((deepC[idx(0, 0)] & 0xFF) <= (shallow[idx(0, 0)] & 0xFF));
        assertNotEquals(0, shallow[idx(0, 0)]);
    }
}
