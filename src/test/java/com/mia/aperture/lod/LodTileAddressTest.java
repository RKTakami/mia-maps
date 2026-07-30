package com.mia.aperture.lod;

import com.mia.aperture.map.MapGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LodTileAddressTest {

    @Test
    void levelZeroIsAlignedForEverySector() {
        // 32-block sections divide both 16384 and 480, so the zoomed-in view — where a fidelity
        // comparison against the existing path is most meaningful — always has a clean mapping.
        for (int sector = 0; sector < MapGeometry.SECTION_COUNT; sector++) {
            assertTrue(LodTileAddress.aligned(0, sector), "sector " + sector);
        }
    }

    @Test
    void coarseLevelsAreMisalignedWhereTheSectorLiftDoesNotCancel() {
        // 480 % 64 == 32, so a level-1 tile in sector 1 straddles two store sections vertically and
        // no single read can serve it. This is the case that would otherwise draw terrain from the
        // wrong depth, looking entirely plausible while being wrong.
        assertFalse(LodTileAddress.aligned(1, 1));
        assertFalse(LodTileAddress.aligned(2, 1));
        // Sector 8 puts shifted Y equal to world Y (480*8 == 3840), so the offset vanishes and every
        // level aligns. Worth pinning: it is the case that makes a naive implementation look correct.
        assertEquals(0, LodTileAddress.verticalOffset(8));
        for (int lvl = 0; lvl <= 4; lvl++) assertTrue(LodTileAddress.aligned(lvl, 8), "lvl " + lvl);
    }

    @Test
    void misalignedTilesReturnNoAddressRatherThanAWrongOne() {
        assertNull(LodTileAddress.bigSection(1, 0, 0, 0, 1),
                "a misaligned tile must refuse, not guess");
        assertNotNull(LodTileAddress.bigSection(0, 0, 0, 0, 1));
    }

    @Test
    void sectorZeroTileMapsThroughTheRimOffset() {
        // Sector 0: worldY = shiftedY - 3840. A tile at shifted section 0 therefore sits 3840 blocks
        // below the origin in vanilla space, which at 32-block sections is 120 sections down.
        int[] a = LodTileAddress.bigSection(0, 0, 0, 0, 0);
        assertNotNull(a);
        assertEquals(0, a[0]);
        assertEquals(-3840 / 32, a[1]);
        assertEquals(0, a[2]);
    }

    @Test
    void sectorShiftsXByItsWholeBand() {
        // Each sector is a 16384-block band along X. A tile at the same shifted X in sector 2 must
        // resolve 32768 blocks further out in vanilla space, or the map would read another layer's
        // terrain — the exact confusion this class exists to prevent.
        int[] s0 = LodTileAddress.bigSection(0, 5, 0, 7, 0);
        int[] s2 = LodTileAddress.bigSection(0, 5, 0, 7, 2);
        assertNotNull(s0);
        assertNotNull(s2);
        assertEquals(s0[0] + 2 * MapGeometry.SECTOR_SPAN_X / 32, s2[0]);
        assertEquals(s0[2], s2[2], "Z is never shifted");
    }

    @Test
    void roundTripsAgainstMapGeometrysOwnShift() {
        // The inverse must agree with the forward transform the map actually uses, not merely look
        // plausible. Any drift between them is a wrong-terrain bug.
        for (int sector : new int[]{0, 1, 3, 8, 14}) {
            for (int worldY : new int[]{-256, -64, 0, 128, 320}) {
                int shifted = MapGeometry.shiftY(worldY, sector);
                assertEquals(worldY, shifted + LodTileAddress.verticalOffset(sector),
                        "sector " + sector + " worldY " + worldY);
            }
        }
    }

    @Test
    void negativeTileCoordinatesFloorRatherThanTruncate()  {
        // Truncation toward zero would put tiles just below an origin into the section above,
        // shifting a whole band of the map by one section.
        //
        // Sector 8 is chosen because it zeroes the VERTICAL offset (480*8 == 3840), which makes the
        // Y flooring observable. It does NOT zero X: every sector still shifts X by its whole
        // 16384-block band, so X here is 8*16384/32 - 1 = 4095 rather than -1. Getting that wrong
        // was the first version of this test, and the distinction is the whole point of the class.
        int[] a = LodTileAddress.bigSection(0, -1, -1, -1, 8);
        assertNotNull(a);
        assertEquals(8 * MapGeometry.SECTOR_SPAN_X / 32 - 1, a[0], "X keeps its sector band");
        assertEquals(-1, a[1], "Y floors, not truncates");
        assertEquals(-1, a[2], "Z is unshifted and floors");
    }

    @Test
    void sectionBlocksGrowsWithLevel() {
        assertEquals(32, LodTileAddress.sectionBlocks(0));
        assertEquals(64, LodTileAddress.sectionBlocks(1));
        assertEquals(512, LodTileAddress.sectionBlocks(4));
    }
}
