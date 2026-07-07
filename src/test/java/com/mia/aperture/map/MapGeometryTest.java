package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapGeometryTest {

    @Test
    void lvlForViewPicksZeroForSmallViews() {
        assertEquals(0, MapGeometry.lvlForView(256));
        assertEquals(0, MapGeometry.lvlForView(512));
    }

    @Test
    void lvlForViewScalesUpAndClamps() {
        assertEquals(1, MapGeometry.lvlForView(1024));
        assertEquals(2, MapGeometry.lvlForView(1100));
        assertEquals(4, MapGeometry.lvlForView(8192));
        assertEquals(4, MapGeometry.lvlForView(100000));
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
        // Live-verified 2026-07-06: worldX=65399, sector 4 -> shifted ~ -137;
        // worldY=-137 sector 4 -> shifted -137 + (240-120)*16 = 1783
        int sector = 4;
        assertEquals(65399 - (sector << 14), MapGeometry.shiftX(65399, sector));
        assertEquals(-137 + (240 - sector * 30) * 16, MapGeometry.shiftY(-137, sector));
    }
}
