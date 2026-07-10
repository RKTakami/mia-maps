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
}
