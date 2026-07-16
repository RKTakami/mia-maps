package com.mia.aperture.map;

import java.util.List;

// Pure helpers for the coarse corridor tier: LOD selection, coarse-cell to shifted-column
// conversion, and picking the local router's next sub-goal along the corridor. No Voxy or
// Minecraft types, so it stays unit-testable (Voxy is compileOnly, off the test classpath).
public final class CorridorMath {
    private CorridorMath() {}

    // Cells needed to span `blocks` at LOD lvl, where one cell = (1 << lvl) blocks. Never below 1.
    public static int cellsFor(int blocks, int lvl) {
        if (blocks <= 1) return 1;
        return ((blocks - 1) >> lvl) + 1;
    }

    public static long cellCount(int spanX, int spanY, int spanZ, int lvl) {
        return (long) cellsFor(spanX, lvl) * cellsFor(spanY, lvl) * cellsFor(spanZ, lvl);
    }

    // The FINEST (smallest) LOD whose cell count fits `budget`, capped at `maxLvl`.
    public static int pickLevel(int spanX, int spanY, int spanZ, long budget, int maxLvl) {
        int lvl = 0;
        while (lvl < maxLvl && cellCount(spanX, spanY, spanZ, lvl) > budget) lvl++;
        return lvl;
    }
}
