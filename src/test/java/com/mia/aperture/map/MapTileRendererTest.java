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

    @Test
    void cellSizeScalesHeightsAndBandClipping() {
        long[] sec = emptySection();
        fillLayer(sec, 10, 1);
        int[] color = new int[1024];
        int[] height = new int[1024];
        // cellSize 4: one section spans 128 blocks; base 0, top face 128
        MapTileRenderer.renderTile(new long[][]{sec}, 128, 128, 0, 4, MapMode.VANILLA, colors, color, height);
        assertEquals(40, height[idx(3, 3)]);
        assertNotEquals(0, color[idx(3, 3)]);

        fillLayer(sec, 2, 1);
        // band top 30 must skip the surface at block 40 and find the one at block 8
        MapTileRenderer.renderTile(new long[][]{sec}, 128, 30, 0, 4, MapMode.VANILLA, colors, color, height);
        assertEquals(8, height[idx(3, 3)]);
    }

    @Test
    void bandBelowStackYieldsTransparentTile() {
        long[] sec = emptySection();
        fillLayer(sec, 10, 1);
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 100, 288, 1, MapMode.VANILLA, colors, color, height);
        assertEquals(0, color[idx(5, 5)]);
        assertEquals(Integer.MIN_VALUE, height[idx(5, 5)]);
    }

    @Test
    void caveModeSolidColumnIsBlack() {
        // a column that never opens into air renders transparent (black background)
        long[] sec = emptySection();
        for (int cy = 0; cy < 32; cy++) fillLayer(sec, cy, 1);
        int[] color = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.CAVE, colors, color, new int[1024]);
        assertEquals(0, color[idx(0, 0)]);
    }

    @Test
    void caveModeDrawsFloorBelowOverburden() {
        // solid roof at the top, an air gap, then a cave floor -> the floor is drawn
        long[] sec = emptySection();
        fillLayer(sec, 31, 1); // overburden at the very top of the band
        fillLayer(sec, 30, 1);
        fillLayer(sec, 10, 1); // cave floor, with air (cells 11-29) above it
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.CAVE, colors, color, height);
        assertNotEquals(0, color[idx(0, 0)]);
        assertEquals(288 + 10, height[idx(0, 0)]);
    }

    @Test
    void caveModeHigherFloorsBrighter() {
        long[] high = emptySection();
        fillLayer(high, 20, 1);
        long[] low = emptySection();
        fillLayer(low, 4, 1);
        int[] hi = new int[1024];
        int[] lo = new int[1024];
        MapTileRenderer.renderTile(new long[][]{high}, 320, 320, 288, 1, MapMode.CAVE, colors, hi, new int[1024]);
        MapTileRenderer.renderTile(new long[][]{low}, 320, 320, 288, 1, MapMode.CAVE, colors, lo, new int[1024]);
        assertNotEquals(0, hi[idx(0, 0)]);
        assertTrue((hi[idx(0, 0)] & 0xFF) > (lo[idx(0, 0)] & 0xFF));
    }

    @Test
    void xrayEmptyColumnIsTransparent() {
        // no surface at all (all air) -> nothing to draw
        int[] color = new int[1024];
        MapTileRenderer.renderTile(new long[][]{emptySection()}, 320, 320, 288, 1, MapMode.XRAY, colors, color, new int[1024]);
        assertEquals(0, color[idx(0, 0)]);
    }

    @Test
    void xraySolidColumnShowsDimSurface() {
        // solid ground with no cave -> the surface is kept (dimmed) for context, not transparent,
        // and it is NOT cave-tinted, so it's dimmer than the same surface in vanilla mode.
        long[] sec = emptySection();
        for (int cy = 0; cy <= 15; cy++) fillLayer(sec, cy, 1); // surface at 15, sky above, solid below
        int[] xrayC = new int[1024];
        int[] vanC = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.XRAY, colors, xrayC, new int[1024]);
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.VANILLA, colors, vanC, new int[1024]);
        assertNotEquals(0, xrayC[idx(0, 0)]);                         // surface context is visible
        assertTrue((xrayC[idx(0, 0)] & 0xFF) < (vanC[idx(0, 0)] & 0xFF)); // but dimmed vs normal
    }

    @Test
    void xrayRevealsBuriedCaveOverDimSurface() {
        // a cave column glows brighter/bluer than a plain (no-cave) surface column
        long[] plain = emptySection();
        for (int cy = 0; cy <= 15; cy++) fillLayer(plain, cy, 1); // no cave
        long[] caved = emptySection();
        for (int cy = 0; cy <= 10; cy++) fillLayer(caved, cy, 1);  // cave floor at 10
        fillLayer(caved, 20, 1);                                   // surface cap, cave air 11..19
        int[] plainC = new int[1024];
        int[] caveC = new int[1024];
        MapTileRenderer.renderTile(new long[][]{plain}, 320, 320, 288, 1, MapMode.XRAY, colors, plainC, new int[1024]);
        MapTileRenderer.renderTile(new long[][]{caved}, 320, 320, 288, 1, MapMode.XRAY, colors, caveC, new int[1024]);
        assertNotEquals(0, caveC[idx(0, 0)]);
        // the cave's cyan glow means more blue than the plain dim surface
        assertTrue((caveC[idx(0, 0)] & 0xFF) > (plainC[idx(0, 0)] & 0xFF));
    }

    @Test
    void xrayBiggerVoidTintsHotter() {
        // SAME cave-floor height (10) so the cave base shading is identical; only the void size
        // differs (surface cap higher => bigger cave). Larger void blends further toward the cyan tint.
        long[] small = emptySection();
        for (int cy = 0; cy <= 10; cy++) fillLayer(small, cy, 1);
        fillLayer(small, 12, 1); // cap at 12 -> 1-cell void (cell 11)
        long[] big = emptySection();
        for (int cy = 0; cy <= 10; cy++) fillLayer(big, cy, 1);
        fillLayer(big, 25, 1);   // cap at 25 -> 14-cell void (cells 11..24)
        int[] sc = new int[1024];
        int[] bc = new int[1024];
        MapTileRenderer.renderTile(new long[][]{small}, 320, 320, 288, 1, MapMode.XRAY, colors, sc, new int[1024]);
        MapTileRenderer.renderTile(new long[][]{big}, 320, 320, 288, 1, MapMode.XRAY, colors, bc, new int[1024]);
        // XRAY tint is cyan-white (0xFF88FFFF, blue=0xFF): more hollow -> higher blue channel
        assertTrue((bc[idx(0, 0)] & 0xFF) > (sc[idx(0, 0)] & 0xFF));
        assertNotEquals(0, sc[idx(0, 0)]);
    }

    @Test
    void waterFloorScanCrossesSectionBoundary() {
        long[] top = emptySection();
        long[] bottom = emptySection();
        // stack base 256, top face 320; top section covers 288..319, bottom 256..287
        for (int cy = 2; cy <= 8; cy++) fillLayer(top, cy, 2);
        fillLayer(bottom, 30, 1);
        int[] twoSec = new int[1024];
        MapTileRenderer.renderTile(new long[][]{top, bottom}, 320, 320, 256, 1, MapMode.VANILLA, colors, twoSec, new int[1024]);

        // single-section reference with identical water depth (10) and stone floor
        long[] ref = emptySection();
        for (int cy = 11; cy <= 20; cy++) fillLayer(ref, cy, 2);
        fillLayer(ref, 10, 1);
        int[] oneSec = new int[1024];
        MapTileRenderer.renderTile(new long[][]{ref}, 320, 320, 288, 1, MapMode.VANILLA, colors, oneSec, new int[1024]);

        assertNotEquals(0, twoSec[idx(4, 4)]);
        assertEquals(oneSec[idx(4, 4)], twoSec[idx(4, 4)]);
    }
}
