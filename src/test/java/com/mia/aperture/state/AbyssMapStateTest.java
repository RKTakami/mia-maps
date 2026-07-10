package com.mia.aperture.state;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.mia.aperture.map.MapGeometry;

class AbyssMapStateTest {

    @Test
    void followModeStartsJustAbovePlayer() {
        int expected = MapGeometry.shiftY(100, 0) + AbyssMapState.PLAYER_CEILING_OFFSET;
        assertEquals(expected, AbyssMapState.mapBandTopShifted(100, 0, false, 9999.0));
    }

    @Test
    void followModeIgnoresCutValue() {
        assertEquals(
                AbyssMapState.mapBandTopShifted(100, 2, false, 0.0),
                AbyssMapState.mapBandTopShifted(100, 2, false, -5000.0));
    }

    @Test
    void activeModeUsesAbsoluteCut() {
        int worldY = (int) (40.0 + 2L * 480);
        int expected = MapGeometry.shiftY(worldY, 2) + AbyssMapState.PLAYER_CEILING_OFFSET;
        assertEquals(expected, AbyssMapState.mapBandTopShifted(999, 2, true, 40.0));
    }

    // A cut equal to the player's abyss depth (cut + sector*480 == playerWorldY) must
    // yield the same band top as follow mode, so resetDepth returns seamlessly.
    @Test
    void resetCutMatchesFollowMode() {
        int playerWorldY = 1000;
        int sector = 2;
        double cut = playerWorldY - (double) (sector * 480);
        assertEquals(
                AbyssMapState.mapBandTopShifted(playerWorldY, sector, false, 0.0),
                AbyssMapState.mapBandTopShifted(playerWorldY, sector, true, cut));
    }

    @Test
    void effectiveBandTopManualSliceWins() {
        int manual = AbyssMapState.mapBandTopShifted(100, 0, true, 500.0);
        assertEquals(manual, AbyssMapState.effectiveBandTop(100, 0, true, true, 500.0, true, 999));
    }

    @Test
    void effectiveBandTopUsesCaveCutWhenActive() {
        int expected = AbyssMapState.caveCutShiftedY(200, 0);
        assertEquals(expected, AbyssMapState.effectiveBandTop(100, 0, true, false, 0.0, true, 200));
    }

    @Test
    void effectiveBandTopFallsBackToFollow() {
        int follow = AbyssMapState.mapBandTopShifted(100, 0, false, 0.0);
        assertEquals(follow, AbyssMapState.effectiveBandTop(100, 0, true, false, 0.0, false, 0));
        assertEquals(follow, AbyssMapState.effectiveBandTop(100, 0, false, false, 0.0, true, 200));
    }
}
