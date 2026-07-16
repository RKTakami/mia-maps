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
    void sectorForXMatchesTheLiveVerifiedShift() {
        // The same live sample the shift test above pins: worldX 65399 is section 4.
        assertEquals(4, MapGeometry.sectorForX(65399));
        assertEquals(0, MapGeometry.sectorForX(0));
        assertEquals(1, MapGeometry.sectorForX(MapGeometry.SECTOR_SPAN_X));
        // Bands are centred on multiples of SECTOR_SPAN_X, so the boundary is at half a span.
        assertEquals(0, MapGeometry.sectorForX(8191));
        assertEquals(1, MapGeometry.sectorForX(8192));
    }

    @Test
    void toShiftedColumnAgreesWithTheIntShiftHelpers() {
        double[] s = MapGeometry.toShiftedColumn(65399, -137, 42);
        assertEquals(MapGeometry.shiftX(65399, 4), s[0], 1e-9);
        assertEquals(MapGeometry.shiftY(-137, 4), s[1], 1e-9);
        assertEquals(42, s[2], 1e-9);
    }

    @Test
    void adjacentSectionsStackVerticallyInTheShiftedColumn() {
        // THE bug this exists to prevent: two points one section apart are 16384 blocks apart in
        // world X, but in the shifted column they sit at the SAME x/z, exactly SECTOR_DEPTH apart
        // vertically. A world-space delta would place the deeper one off the far side of the map;
        // the shifted delta correctly places it directly below.
        double[] upper = MapGeometry.toShiftedColumn(0, 0, 0);
        double[] lower = MapGeometry.toShiftedColumn(MapGeometry.SECTOR_SPAN_X, 0, 0);
        assertEquals(upper[0], lower[0], 1e-9);
        assertEquals(upper[2], lower[2], 1e-9);
        assertEquals(MapGeometry.SECTOR_DEPTH, upper[1] - lower[1], 1e-9);
    }

    @Test
    void abyssDepthIsZeroAtTheRimAndNegativeBelow() {
        assertEquals(0, MapGeometry.abyssDepth(MapGeometry.RIM_SHIFTED_Y), 1e-9);
        assertEquals(-7200, MapGeometry.abyssDepth(MapGeometry.RIM_SHIFTED_Y - 7200), 1e-9);
    }

    @Test
    void everyAbyssSectionLandsInsideTheSampledBand() {
        // 15 sections x 480 blocks each must all fall within the band the sampler will look at,
        // or waypoints on the deepest layers would project outside the cloud entirely.
        for (int sector = 0; sector < 15; sector++) {
            double[] s = MapGeometry.toShiftedColumn((double) sector * MapGeometry.SECTOR_SPAN_X, 0, 0);
            assertTrue(s[1] <= MapGeometry.ABYSS_SHIFTED_Y_TOP,
                    "section " + sector + " shiftedY " + s[1] + " above the band top");
            assertTrue(s[1] >= MapGeometry.ABYSS_SHIFTED_Y_BOTTOM,
                    "section " + sector + " shiftedY " + s[1] + " below the band bottom");
        }
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

    @Test
    void closeZoomIsNotClampedVertically() {
        // a modest extent well inside the band must pass through untouched (no regression)
        int atRim = 3840;
        int[] v = MapGeometry.clampVerticalToAbyss(atRim, 192, 192, 8);
        assertEquals(192, v[0]);
        assertEquals(192, v[1]);
    }

    @Test
    void wideZoomClampsToTheAbyssBand() {
        // max zoom asks for ~24576 each way (~49k total) but the Abyss is only ~7.8k tall
        int atRim = 3840;
        int[] v = MapGeometry.clampVerticalToAbyss(atRim, 24576, 24576, 8);
        assertEquals(MapGeometry.ABYSS_SHIFTED_Y_TOP - atRim, v[0]);      // only headroom above
        assertEquals(atRim - MapGeometry.ABYSS_SHIFTED_Y_BOTTOM, v[1]);   // the whole depth below
        int total = v[0] + v[1];
        assertEquals(MapGeometry.ABYSS_SHIFTED_Y_TOP - MapGeometry.ABYSS_SHIFTED_Y_BOTTOM, total);
        assertTrue(total < 9000, "the whole Abyss band, not ~49k of empty space");
    }

    @Test
    void wideZoomFromTheDeepStillSpansEveryLayer() {
        // standing deep, a wide view must still reach up to the rim and down to the floor
        int deep = -3000; // shiftedY, i.e. ~6840 blocks down
        int[] v = MapGeometry.clampVerticalToAbyss(deep, 24576, 24576, 8);
        assertEquals(MapGeometry.ABYSS_SHIFTED_Y_TOP - deep, v[0]);
        assertEquals(deep - MapGeometry.ABYSS_SHIFTED_Y_BOTTOM, v[1]);
        assertEquals(MapGeometry.ABYSS_SHIFTED_Y_TOP - MapGeometry.ABYSS_SHIFTED_Y_BOTTOM,
                v[0] + v[1]); // same full band regardless of where you stand
    }

    @Test
    void focusOutsideTheBandStillYieldsAValidSlab() {
        int wayAbove = MapGeometry.ABYSS_SHIFTED_Y_TOP + 5000;
        int[] v = MapGeometry.clampVerticalToAbyss(wayAbove, 24576, 24576, 8);
        assertEquals(8, v[0]); // never negative/zero
        assertTrue(v[1] > 0);
    }
}
