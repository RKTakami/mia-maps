package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapGeometryTest {

    @Test
    void lvlForViewPicksZeroForSmallViews() {
        int lvl0Max = MapGeometry.TILE_CELLS * MapGeometry.DETAIL_TILES;
        assertEquals(0, MapGeometry.lvlForView(0));
        assertEquals(0, MapGeometry.lvlForView(256));
        assertEquals(0, MapGeometry.lvlForView(lvl0Max)); // top of level 0
    }

    @Test
    void lvlForViewScalesUpAndClampsAtDisplayMax() {
        // Thresholds derive from the constants, so DETAIL_TILES tuning won't break this.
        int step = MapGeometry.TILE_CELLS * MapGeometry.DETAIL_TILES;
        assertEquals(1, MapGeometry.lvlForView(step + 1));
        assertEquals(1, MapGeometry.lvlForView(step * 2));
        assertEquals(2, MapGeometry.lvlForView(step * 2 + 1));
        assertEquals(MapGeometry.MAX_DISPLAY_LVL, MapGeometry.lvlForView(step * 100));
        assertTrue(MapGeometry.lvlForView(Integer.MAX_VALUE / 2) <= MapGeometry.MAX_DISPLAY_LVL);
    }

    @Test
    void tileSpanBlocks() {
        assertEquals(32, MapGeometry.tileSpanBlocks(0));
        assertEquals(512, MapGeometry.tileSpanBlocks(4));
    }

    @Test
    void blockToTileFloorsNegatives() {
        assertEquals(0, MapGeometry.blockToTile(0, 0));
        assertEquals(0, MapGeometry.blockToTile(31, 0));
        assertEquals(-1, MapGeometry.blockToTile(-1, 0));
        assertEquals(-1, MapGeometry.blockToTile(-512, 4));
        assertEquals(-2, MapGeometry.blockToTile(-513, 4));
    }

    @Test
    void bandKeyQuantizesTo16() {
        assertEquals(MapGeometry.bandKey(100), MapGeometry.bandKey(111));
        assertNotEquals(MapGeometry.bandKey(100), MapGeometry.bandKey(116));
        assertEquals(MapGeometry.bandKey(-1), MapGeometry.bandKey(-16));
    }

    @Test
    void shiftMathMatchesVerifiedLiveValues() {
        // Live-verified 2026-07-06 in the Modrinth instance logs
        assertEquals(-137, MapGeometry.shiftX(65399, 4));
        assertEquals(1783, MapGeometry.shiftY(-137, 4));
    }

    @Test
    void playerMarkerCentersWhenUnpanned() {
        assertEquals(400, MapGeometry.playerMarkerX(0.0, 400, 800));
        assertEquals(300, MapGeometry.playerMarkerY(0.0, 300, 600));
    }

    @Test
    void playerMarkerHitsEdgeAtHalfSpanPan() {
        assertEquals(0,   MapGeometry.playerMarkerX(200.0, 400, 800));
        assertEquals(800, MapGeometry.playerMarkerX(-200.0, 400, 800));
        assertEquals(0,   MapGeometry.playerMarkerY(150.0, 300, 600));
        assertEquals(600, MapGeometry.playerMarkerY(-150.0, 300, 600));
    }

    @Test
    void playerMarkerPositivePanMovesTowardOrigin() {
        assertTrue(MapGeometry.playerMarkerX(100.0, 400, 800) < 400);
        assertTrue(MapGeometry.playerMarkerY(75.0, 300, 600) < 300);
    }

    @Test
    void screenOffsetPixelCentersAndReachesEdge() {
        assertEquals(400, MapGeometry.screenOffsetPixel(0.0, 400, 800));
        assertEquals(0,   MapGeometry.screenOffsetPixel(-200.0, 400, 800));
        assertEquals(800, MapGeometry.screenOffsetPixel(200.0, 400, 800));
    }

    @Test
    void playerMarkerMatchesScreenOffsetOfNegPan() {
        assertEquals(MapGeometry.screenOffsetPixel(-100.0, 400, 800),
                MapGeometry.playerMarkerX(100.0, 400, 800));
    }
}
