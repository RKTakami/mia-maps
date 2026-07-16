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

    // Shifted-column centre of coarse cell (cx,cy,cz), given the grid's origin in CELLS and the LOD
    // (cell = 1 << lvl blocks).
    public static double[] cellCentreShifted(int cx, int cy, int cz,
            int originCellX, int originCellY, int originCellZ, int lvl) {
        int size = 1 << lvl;
        return new double[]{
                (double) (originCellX + cx) * size + size / 2.0,
                (double) (originCellY + cy) * size + size / 2.0,
                (double) (originCellZ + cz) * size + size / 2.0};
    }

    // The corridor point (ordered player->destination, shifted coords) to aim the local router at:
    // the farthest point still within `reach` blocks of the player, scanning forward from the point
    // nearest the player. Falls back to that nearest point, or null when the corridor is empty.
    public static double[] subGoal(List<double[]> corridor, double px, double py, double pz, double reach) {
        if (corridor.isEmpty()) return null;
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < corridor.size(); i++) {
            double d = dist2(corridor.get(i), px, py, pz);
            if (d < best) { best = d; nearest = i; }
        }
        double reach2 = reach * reach;
        double[] pick = corridor.get(nearest);
        for (int i = nearest; i < corridor.size(); i++) {
            if (dist2(corridor.get(i), px, py, pz) <= reach2) pick = corridor.get(i);
            else break;
        }
        return pick;
    }

    private static double dist2(double[] c, double x, double y, double z) {
        double dx = c[0] - x, dy = c[1] - y, dz = c[2] - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
