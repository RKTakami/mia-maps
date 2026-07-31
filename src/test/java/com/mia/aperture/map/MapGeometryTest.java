package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapGeometryTest {

    // ---- the Abyss coordinate model ---------------------------------------------------------
    //
    // These mirror Voxy's AbyssUtil, which is compileOnly and cannot be on the test classpath (the
    // jar is not redistributable, so CI does not have it). The mirror was verified against the real
    // implementation locally over 1.4 million points with zero mismatches; what these tests do is
    // pin the two behaviours that a later "tidy-up" would plausibly break, since neither is what you
    // would write from scratch.

    @Test
    void theViewersOwnLayerIsNeverMoved() {
        // The property the whole feature rests on: with no layer offset the transform is the
        // identity, so turning cross-layer rendering on cannot disturb the terrain you are standing
        // in. A regression here would move the world under the player.
        for (int x : new int[]{-40000, 0, 131193, 999999}) {
            assertEquals(x, MapGeometry.stackedDrawX(x, 0));
            assertEquals(x, MapGeometry.stackedDrawY(x, 0));
        }
    }

    @Test
    void theLayerAboveIsSlidBackOverTheViewerAndLiftedOneDepth() {
        // Sector 7 seen from sector 8: offset -1. Its terrain lives 16384 blocks west and has to
        // come back over the player, and rise by one SECTOR_DEPTH.
        assertEquals(131193, MapGeometry.stackedDrawX(131193 - MapGeometry.SECTOR_SPAN_X, -1));
        assertEquals(-28 + MapGeometry.SECTOR_DEPTH, MapGeometry.stackedDrawY(-28, -1));
        // And the layer below goes the other way, symmetrically.
        assertEquals(131193, MapGeometry.stackedDrawX(131193 + MapGeometry.SECTOR_SPAN_X, 1));
        assertEquals(-28 - MapGeometry.SECTOR_DEPTH, MapGeometry.stackedDrawY(-28, 1));
    }

    @Test
    void layersStackAtEvenDepthsWithoutDrift() {
        // Five layers down must land exactly five depths below, not accumulate rounding — the
        // transform is integer arithmetic precisely so a distant layer cannot drift out of line.
        for (int l = -6; l <= 6; l++) {
            assertEquals(-l * MapGeometry.SECTOR_DEPTH, MapGeometry.stackedDrawY(0, l));
            assertEquals(-l * MapGeometry.SECTOR_SPAN_X, MapGeometry.stackedDrawX(0, l));
        }
    }

    @Test
    void sectorTruncatesTowardZeroWhichMakesSectorZeroDoubleWidth() {
        assertEquals(0, MapGeometry.sectorForX(0));
        assertEquals(0, MapGeometry.sectorForX(8191));
        assertEquals(1, MapGeometry.sectorForX(8192), "half a sector rounds up into the next");
        assertEquals(1, MapGeometry.sectorForX(16384));
        assertEquals(2, MapGeometry.sectorForX(24576));

        // Going west, the boundary is NOT the mirror image, and this is the whole reason the
        // truncation matters. (int) truncates toward zero, so -0.99994 becomes 0 where a floor
        // would give -1 — which means sector 0 runs from -24575 all the way to 8191, twice the
        // width of every other sector, while sector -1 starts only at -24576.
        assertEquals(0, MapGeometry.sectorForX(-1));
        assertEquals(0, MapGeometry.sectorForX(-8192));
        assertEquals(0, MapGeometry.sectorForX(-16384), "still sector 0, a whole span west");
        assertEquals(0, MapGeometry.sectorForX(-24575), "the last x that is still sector 0");
        assertEquals(-1, MapGeometry.sectorForX(-24576), "one further west flips it");
        assertEquals(-2, MapGeometry.sectorForX(-40960));

        // Verified against Voxy's own AbyssUtil, which is what the database is keyed on. This is
        // arguably a quirk in the original, and reproducing it is deliberate: "correcting" it to a
        // floor would look up the wrong sector for every negative X and silently mismap terrain.
    }

    @Test
    void abyssXIsARemainderNotAModuloSoItStaysSignedWithTheInput() {
        // Mid-sector: centred on the sector, so the middle reads as 0.
        assertEquals(0.0, MapGeometry.toAbyss(0, 0).x(), 1e-9);
        assertEquals(0.0, MapGeometry.toAbyss(16384, 0).x(), 1e-9);
        assertEquals(100.0, MapGeometry.toAbyss(100, 0).x(), 1e-9);

        // The case that separates % from a true modulo. A modulo would land these a full 16384
        // apart from the remainder, putting the player on the opposite side of the Abyss.
        assertEquals(-100.0, MapGeometry.toAbyss(-100, 0).x(), 1e-9);
        assertEquals(-8192.0, MapGeometry.toAbyss(-8192, 0).x(), 1e-9);
        assertTrue(MapGeometry.toAbyss(-3000, 0).x() < 0,
                "a point west of the origin must stay west of it");
    }

    @Test
    void abyssDepthSubtractsTheSectorsOwnLift() {
        assertEquals(63.0, MapGeometry.toAbyss(0, 63).y(), 1e-9);
        // One sector east, the same world Y is 480 deeper in Abyss terms.
        assertEquals(63.0 - 480.0, MapGeometry.toAbyss(16384, 63).y(), 1e-9);
        // West it does NOT mirror, because of the double-width sector 0 above: -16384 is still
        // sector 0, so its depth is unshifted. The lift only appears past -24576.
        assertEquals(63.0, MapGeometry.toAbyss(-16384, 63).y(), 1e-9);
        assertEquals(63.0 + 480.0, MapGeometry.toAbyss(-24576, 63).y(), 1e-9);

        // Depth steps by exactly one SECTOR_DEPTH where the sector changes, in both directions.
        assertEquals(MapGeometry.SECTOR_DEPTH,
                MapGeometry.toAbyss(8191, 0).y() - MapGeometry.toAbyss(8192, 0).y(), 1e-9);
        assertEquals(MapGeometry.SECTOR_DEPTH,
                MapGeometry.toAbyss(-24576, 0).y() - MapGeometry.toAbyss(-24575, 0).y(), 1e-9);
    }

    @Test
    void abyssXCanExceedHalfASectorInsideTheDoubleWidthSectorZero() {
        // A consequence of the same quirk, pinned because it looks like a bug and is not: inside
        // sector 0's westward overhang the offset from centre legitimately runs past 8192.
        assertEquals(-16384.0, MapGeometry.toAbyss(-16384, 0).x(), 1e-9);
        assertEquals(-24575.0, MapGeometry.toAbyss(-24575, 0).x(), 1e-9);
        // And snaps back inside the usual range as soon as the sector flips.
        assertEquals(-8192.0, MapGeometry.toAbyss(-24576, 0).x(), 1e-9);
    }

    @Test
    void toAbyssAgreesWithTheShiftHelpersItSharesConstantsWith() {
        // shiftY and toAbyss both place a world Y in the sector's own frame, so they must not drift:
        // shiftY adds the rim offset, toAbyss does not, and the difference must be exactly that.
        for (int x : new int[]{-40960, -24576, -20000, -1, 0, 5000, 16384, 40000}) {
            int sector = MapGeometry.sectorForX(x);
            assertEquals(MapGeometry.shiftY(63, sector),
                    MapGeometry.toAbyss(x, 63).y() + MapGeometry.RIM_SHIFTED_Y, 1e-9,
                    "drifted at x=" + x);
        }
    }

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
    void overlapIsThirtyTwoBlocks() {
        // Sections are 512 tall but step 480 -- the 32-block overlap is the band you walk down
        // through from one layer to the next. If these constants ever disagree, sectorForShiftedY's
        // tie-breaking is meaningless.
        assertEquals(32, MapGeometry.SECTION_WORLD_Y_HEIGHT - MapGeometry.SECTOR_DEPTH);
    }

    @Test
    void toWorldInvertsToShiftedColumnForEverySection() {
        for (int sector = 0; sector < MapGeometry.SECTION_COUNT; sector++) {
            double worldX = (double) sector * MapGeometry.SECTOR_SPAN_X + 100;
            double[] s = MapGeometry.toShiftedColumn(worldX, 12, -7);
            double[] w = MapGeometry.toWorld(s[0], s[1], s[2], sector);
            assertEquals(worldX, w[0], 1e-9, "section " + sector);
            assertEquals(12, w[1], 1e-9, "section " + sector);
            assertEquals(-7, w[2], 1e-9, "section " + sector);
        }
    }

    @Test
    void sectorForShiftedYFindsTheOwningSection() {
        // worldY 0 sits mid-band, so exactly one section owns it -- the preference cannot matter.
        for (int sector = 0; sector < MapGeometry.SECTION_COUNT; sector++) {
            double shiftedY = MapGeometry.shiftY(0, sector);
            assertEquals(sector, MapGeometry.sectorForShiftedY(shiftedY, 0), "section " + sector);
            assertEquals(sector, MapGeometry.sectorForShiftedY(shiftedY, MapGeometry.SECTION_COUNT - 1),
                    "section " + sector);
        }
    }

    @Test
    void overlapBandPrefersTheRequestedSection() {
        // worldY -240 of section 3 IS worldY 240 of section 4 -- the same place, reached by walking
        // down. Both answers are correct, so the caller's preference decides; that keeps a
        // descending route on one layer instead of flickering between two equally valid answers.
        double shiftedY = MapGeometry.shiftY(-240, 3);
        assertTrue(MapGeometry.sectorContainsShiftedY(3, shiftedY));
        assertTrue(MapGeometry.sectorContainsShiftedY(4, shiftedY));
        assertEquals(3, MapGeometry.sectorForShiftedY(shiftedY, 3));
        assertEquals(4, MapGeometry.sectorForShiftedY(shiftedY, 4));
    }

    @Test
    void sectorForShiftedYIgnoresAnImpossiblePreference() {
        double shiftedY = MapGeometry.shiftY(0, 7);
        assertEquals(7, MapGeometry.sectorForShiftedY(shiftedY, 0));
        assertEquals(7, MapGeometry.sectorForShiftedY(shiftedY, 14));
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

    @Test
    void worldDeltaFromPixelIsInverseOfScreenOffsetPixel() {
        int[] dims = {256, 400, 1080};
        int[] spans = {64, 512, 4096};
        for (int dim : dims) {
            for (int span : spans) {
                for (double delta : new double[]{-span / 2.0, -37.5, 0, 12.0, span / 2.0 - 1}) {
                    int px = MapGeometry.screenOffsetPixel(delta, span, dim);
                    double back = MapGeometry.worldDeltaFromPixel(px, span, dim);
                    // round-trip within one block-per-pixel of rounding error
                    assertEquals(delta, back, (double) span / dim + 1e-6,
                            "dim=" + dim + " span=" + span + " delta=" + delta);
                }
            }
        }
    }

    @Test
    void centerPixelMapsToZeroDelta() {
        assertEquals(0.0, MapGeometry.worldDeltaFromPixel(200, 800, 400), 1e-9); // pixel dim/2 -> 0
    }
}
