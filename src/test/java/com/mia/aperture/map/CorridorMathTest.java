package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CorridorMathTest {

    @Test
    void cellsForCeilingDividesByCellSize() {
        assertEquals(1, CorridorMath.cellsFor(1, 0));
        assertEquals(16, CorridorMath.cellsFor(16, 0));
        assertEquals(1, CorridorMath.cellsFor(16, 4));  // 16 blocks / 16-block cell = 1
        assertEquals(2, CorridorMath.cellsFor(17, 4));  // ceil(17/16) = 2
        assertEquals(1, CorridorMath.cellsFor(0, 0));   // never below 1
    }

    @Test
    void cellCountMultipliesTheThreeAxes() {
        assertEquals(256L * 8 * 256, CorridorMath.cellCount(256, 8, 256, 0));
    }

    @Test
    void pickLevelStaysAtZeroWhenItFits() {
        assertEquals(0, CorridorMath.pickLevel(64, 64, 64, 4_000_000, 4));
    }

    @Test
    void pickLevelClimbsToTheFinestThatFits() {
        // The straight-down case from the spec: 256 x 7968 x 256 blocks.
        int lvl = CorridorMath.pickLevel(256, 7968, 256, 4_000_000, 4);
        assertTrue(CorridorMath.cellCount(256, 7968, 256, lvl) <= 4_000_000,
                "level " + lvl + " must fit the budget");
        assertTrue(lvl == 0 || CorridorMath.cellCount(256, 7968, 256, lvl - 1) > 4_000_000,
                "level " + lvl + " must be the FINEST that fits");
    }

    @Test
    void pickLevelNeverExceedsTheCap() {
        // An enormous box that does not fit even at the cap still returns the cap.
        assertEquals(4, CorridorMath.pickLevel(40000, 40000, 40000, 4_000_000, 4));
    }
}
