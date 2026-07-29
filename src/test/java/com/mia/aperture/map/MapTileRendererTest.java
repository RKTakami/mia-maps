package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapTileRendererTest {

    private static final int STONE = 0xFF808080;
    private static final int WATER = 0xFF4040FF;

    // Mapping ids in tests: 0 = air, 1 = stone, 2 = water
    private final MapColorSource colors = new MapColorSource() {
        @Override public int baseColor(long id, Face face) { return id == 1 ? STONE : id == 2 ? WATER : 0; }
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
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, 320, MapMode.VANILLA, colors, color, height);
        assertEquals(288 + 10, height[idx(5, 5)]);
        assertNotEquals(0, color[idx(5, 5)]);
    }

    @Test
    void emptyColumnIsTransparent() {
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{emptySection()}, 320, 320, 288, 1, 320, MapMode.VANILLA, colors, color, height);
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
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 300, 288, 1, 300, MapMode.VANILLA, colors, color, height);
        assertEquals(293, height[idx(0, 0)]);
    }

    @Test
    void nullSectionsAreSkipped() {
        long[] sec = emptySection();
        fillLayer(sec, 0, 1); // block Y 288 in the SECOND section (base 256)
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{null, sec}, 352, 352, 256, 1, 352, MapMode.VANILLA, colors, color, height);
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
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, 320, MapMode.VANILLA, colors, color, height);
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
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, 320, MapMode.RELIEF, colors, color, height);
        int slope = color[idx(5, 10)];
        // flat reference tile
        long[] flatSec = emptySection();
        fillLayer(flatSec, 10, 1);
        int[] flatColor = new int[1024];
        MapTileRenderer.renderTile(new long[][]{flatSec}, 320, 320, 288, 1, 320, MapMode.RELIEF, colors, flatColor, new int[1024]);
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
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, 320, MapMode.VANILLA, colors, shallow, new int[1024]);
        MapTileRenderer.renderTile(new long[][]{deep}, 320, 320, 288, 1, 320, MapMode.VANILLA, colors, deepC, new int[1024]);
        // deeper water is darker, both are water-ish (blue channel dominant)
        assertTrue((deepC[idx(0, 0)] & 0xFF) <= (shallow[idx(0, 0)] & 0xFF));
        assertNotEquals(0, shallow[idx(0, 0)]);
    }

    @Test
    void cellSizeScalesHeightsAndBandClipping() {
        long[] sec = emptySection();
        fillLayer(sec, 10, 1);
        int[] color = new int[1024];
        int[] height = new int[1024];
        // cellSize 4: one section spans 128 blocks; base 0, top face 128
        MapTileRenderer.renderTile(new long[][]{sec}, 128, 128, 0, 4, 128, MapMode.VANILLA, colors, color, height);
        assertEquals(40, height[idx(3, 3)]);
        assertNotEquals(0, color[idx(3, 3)]);

        fillLayer(sec, 2, 1);
        // band top 30 must skip the surface at block 40 and find the one at block 8
        MapTileRenderer.renderTile(new long[][]{sec}, 128, 30, 0, 4, 30, MapMode.VANILLA, colors, color, height);
        assertEquals(8, height[idx(3, 3)]);
    }

    @Test
    void bandBelowStackYieldsTransparentTile() {
        long[] sec = emptySection();
        fillLayer(sec, 10, 1);
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 100, 288, 1, 100, MapMode.VANILLA, colors, color, height);
        assertEquals(0, color[idx(5, 5)]);
        assertEquals(Integer.MIN_VALUE, height[idx(5, 5)]);
    }

    // --- MapMode.CAVES ------------------------------------------------------------------------
    // These are the tests that keep the depth slice from drifting back into the X-ray removed in
    // dfeb3e5. The first three are the compliance argument, not decoration: on open ground the
    // slice is identical to RELIEF, and underground it paints strictly LESS than the normal map,
    // never seeing through rock into terrain the other modes hide.

    @Test
    void caveModeMatchesReliefHeightOnOpenGround() {
        long[] sec = emptySection();
        fillLayer(sec, 10, 1);
        int[] reliefH = new int[1024];
        int[] caveH = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 319, 288, 1, 319, MapMode.RELIEF, colors, new int[1024], reliefH);
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 319, 288, 1, 319, MapMode.CAVES, colors, new int[1024], caveH);
        assertEquals(reliefH[idx(5, 5)], caveH[idx(5, 5)]);
        assertEquals(298, caveH[idx(5, 5)]);
    }

    @Test
    void caveModeLeavesSolidRockUnpainted() {
        long[] sec = emptySection();
        for (int cy = 0; cy < 32; cy++) fillLayer(sec, cy, 1);   // embedded in rock
        int[] vanilla = new int[1024];
        int[] caves = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 319, 288, 1, 319, MapMode.VANILLA, colors, vanilla, new int[1024]);
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 319, 288, 1, 319, MapMode.CAVES, colors, caves, new int[1024]);
        assertNotEquals(0, vanilla[idx(5, 5)], "the normal map paints the rock you are inside");
        assertEquals(0, caves[idx(5, 5)], "the slice must leave it dark — that is what makes caves legible");
    }

    @Test
    void caveModeCannotSeeThroughThickRock() {
        long[] sec = emptySection();
        for (int cy = 20; cy < 32; cy++) fillLayer(sec, cy, 1);  // 12 cells of ceiling
        fillLayer(sec, 10, 1);                                   // a cave floor well beneath it
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 319, 288, 1, 319, MapMode.CAVES, colors, color, height);
        assertEquals(0, color[idx(5, 5)]);
        assertEquals(Integer.MIN_VALUE, height[idx(5, 5)]);
    }

    @Test
    void caveModeDrawsFloorUnderAThinLip() {
        long[] sec = emptySection();
        fillLayer(sec, 31, 1);   // a one-cell lip at the band top
        fillLayer(sec, 29, 1);   // floor just under it, open above
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 319, 288, 1, 319, MapMode.CAVES, colors, new int[1024], height);
        assertEquals(288 + 29, height[idx(5, 5)]);
    }

    @Test
    void caveModeDrawsThePassageFloor() {
        long[] sec = emptySection();
        fillLayer(sec, 19, 1);   // standing in an open passage, floor 12 cells down
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 319, 288, 1, 319, MapMode.CAVES, colors, color, height);
        assertEquals(288 + 19, height[idx(5, 5)]);
        assertNotEquals(0, color[idx(5, 5)]);
    }

    @Test
    void caveSliceStopsAtItsDepthBound() {
        long[] top = emptySection();
        long[] bottom = emptySection();
        // stack 256..319; band top 319, so the slice reaches down to 319-48 = 271
        fillLayer(bottom, 4, 1);           // block 260, open above, but 59 blocks down
        int[] reliefH = new int[1024];
        int[] caveH = new int[1024];
        MapTileRenderer.renderTile(new long[][]{top, bottom}, 320, 319, 256, 1, 319, MapMode.RELIEF, colors, new int[1024], reliefH);
        MapTileRenderer.renderTile(new long[][]{top, bottom}, 320, 319, 256, 1, 319, MapMode.CAVES, colors, new int[1024], caveH);
        assertEquals(260, reliefH[idx(5, 5)], "the normal map finds it");
        assertEquals(Integer.MIN_VALUE, caveH[idx(5, 5)], "the slice does not reach that far");
    }

    @Test
    void caveModeShadesDeeperFloorsDarker() {
        long[] shallowSec = emptySection();
        fillLayer(shallowSec, 30, 1);      // 1 block below the band top
        long[] deepSec = emptySection();
        fillLayer(deepSec, 0, 1);          // 31 blocks below it
        int[] shallow = new int[1024];
        int[] deep = new int[1024];
        MapTileRenderer.renderTile(new long[][]{shallowSec}, 320, 319, 288, 1, 319, MapMode.CAVES, colors, shallow, new int[1024]);
        MapTileRenderer.renderTile(new long[][]{deepSec}, 320, 319, 288, 1, 319, MapMode.CAVES, colors, deep, new int[1024]);
        assertNotEquals(0, shallow[idx(5, 5)]);
        assertNotEquals(0, deep[idx(5, 5)]);
        assertTrue(((deep[idx(5, 5)] >> 16) & 0xFF) < ((shallow[idx(5, 5)] >> 16) & 0xFF),
                "the same stone floor further below the player must read darker");
    }

    @Test
    void waterFloorScanCrossesSectionBoundary() {
        long[] top = emptySection();
        long[] bottom = emptySection();
        // stack base 256, top face 320; top section covers 288..319, bottom 256..287
        for (int cy = 2; cy <= 8; cy++) fillLayer(top, cy, 2);
        fillLayer(bottom, 30, 1);
        int[] twoSec = new int[1024];
        MapTileRenderer.renderTile(new long[][]{top, bottom}, 320, 320, 256, 1, 320, MapMode.VANILLA, colors, twoSec, new int[1024]);

        // single-section reference with identical water depth (10) and stone floor
        long[] ref = emptySection();
        for (int cy = 11; cy <= 20; cy++) fillLayer(ref, cy, 2);
        fillLayer(ref, 10, 1);
        int[] oneSec = new int[1024];
        MapTileRenderer.renderTile(new long[][]{ref}, 320, 320, 288, 1, 320, MapMode.VANILLA, colors, oneSec, new int[1024]);

        assertNotEquals(0, twoSec[idx(4, 4)]);
        assertEquals(oneSec[idx(4, 4)], twoSec[idx(4, 4)]);
    }

    @Test
    void caveModeTintsSurfacesAboveTheReferenceRed() {
        // With the depth cut engaged the band top can sit above the player, so the map draws
        // terrain over their head. Measuring from the player rather than the scan line is what
        // makes that read as "above me" instead of "just below the cut".
        long[] sec = emptySection();
        fillLayer(sec, 20, 1);                       // surface at 308
        int[] above = new int[1024];
        int[] below = new int[1024];
        // reference 290: the surface is 18 blocks ABOVE the player
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 319, 288, 1, 290, MapMode.CAVES, colors, above, new int[1024]);
        // reference 319: the same surface is 11 blocks BELOW the player
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 319, 288, 1, 319, MapMode.CAVES, colors, below, new int[1024]);
        int a = above[idx(5, 5)], bl = below[idx(5, 5)];
        assertNotEquals(0, a);
        assertTrue(((a >> 16) & 0xFF) > (a & 0xFF), "above the player reads red");
        assertTrue((bl & 0xFF) > ((bl >> 16) & 0xFF), "below the player reads blue");
    }
}
