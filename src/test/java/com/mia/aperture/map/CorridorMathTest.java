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

    @Test
    void cellCentreIsTheMiddleOfTheCoarseCell() {
        // origin cell (10,20,30) in cells, cell 0,0,0, at lvl 4 (16-block cells).
        double[] c = CorridorMath.cellCentreShifted(0, 0, 0, 10, 20, 30, 4);
        assertEquals(10 * 16 + 8, c[0], 1e-9);
        assertEquals(20 * 16 + 8, c[1], 1e-9);
        assertEquals(30 * 16 + 8, c[2], 1e-9);
    }

    @Test
    void subGoalOfAnEmptyCorridorIsNull() {
        assertNull(CorridorMath.subGoal(java.util.List.of(), 0, 0, 0, 72));
    }

    @Test
    void subGoalPicksTheFarthestPointWithinReach() {
        // Corridor straight down from the player. Reach 72 -> the point at y=-72 is the farthest
        // within reach; y=-100 is beyond it.
        java.util.List<double[]> corridor = java.util.List.of(
                new double[]{0, 0, 0},
                new double[]{0, -40, 0},
                new double[]{0, -72, 0},
                new double[]{0, -100, 0});
        double[] g = CorridorMath.subGoal(corridor, 0, 0, 0, 72);
        assertArrayEquals(new double[]{0, -72, 0}, g, 1e-9);
    }

    @Test
    void subGoalFallsBackToTheNearestPointWhenNoneAreWithinReach() {
        java.util.List<double[]> corridor = java.util.List.of(
                new double[]{0, -200, 0},
                new double[]{0, -240, 0});
        double[] g = CorridorMath.subGoal(corridor, 0, 0, 0, 72);
        assertArrayEquals(new double[]{0, -200, 0}, g, 1e-9);
    }
}
