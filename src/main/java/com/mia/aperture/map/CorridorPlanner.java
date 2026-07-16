package com.mia.aperture.map;

import me.cortex.voxy.common.world.WorldEngine;

import java.util.ArrayList;
import java.util.List;

// Tier 1 of cross-layer routing: a coarse "which shaft to head down" corridor over the whole Abyss
// column. Samples Voxy at the finest LOD (<= 4) whose cell count fits CELL_BUDGET across a padded
// player->destination box in the shifted column, then runs CorridorFinder through the non-opaque
// cells. Returns the corridor as an ordered list of shifted-column points (player -> destination),
// or empty when nothing could be sampled. Voxy contact is quarantined here.
public final class CorridorPlanner {
    private static final long CELL_BUDGET = 4_000_000L;
    private static final int PAD = 128;             // horizontal margin (blocks) around the span
    private static final int HORIZ_CAP = 1024;      // max horizontal span (blocks); clips far goals
    private static final int MAX_CORRIDOR_LVL = 4;  // Voxy stores nothing coarser (project_memory §3b)
    private static final int NODE_CAP = 400_000;

    private CorridorPlanner() {}

    // p and t are the player and destination in the shifted column.
    public static List<double[]> plan(WorldEngine engine, MapColorSource colors,
            double[] p, double[] t) {
        // Axis-aligned span covering both endpoints, padded horizontally. The vertical extent is
        // kept whole (the Abyss column is the point); the horizontal extent is capped and clipped
        // toward the goal so a far-horizontal waypoint yields a PARTIAL corridor that progresses as
        // you travel, rather than a multi-hundred-MB grid at LOD 4.
        int[] xr = clipToward((int) Math.floor(Math.min(p[0], t[0])) - PAD,
                (int) Math.ceil(Math.max(p[0], t[0])) + PAD,
                (int) Math.floor(p[0]), (int) Math.floor(t[0]), HORIZ_CAP);
        int[] zr = clipToward((int) Math.floor(Math.min(p[2], t[2])) - PAD,
                (int) Math.ceil(Math.max(p[2], t[2])) + PAD,
                (int) Math.floor(p[2]), (int) Math.floor(t[2]), HORIZ_CAP);
        int minX = xr[0], maxX = xr[1];
        int minY = (int) Math.floor(Math.min(p[1], t[1]));
        int maxY = (int) Math.ceil(Math.max(p[1], t[1]));
        int minZ = zr[0], maxZ = zr[1];
        int spanX = maxX - minX, spanY = maxY - minY, spanZ = maxZ - minZ;

        int lvl = CorridorMath.pickLevel(spanX, spanY, spanZ, CELL_BUDGET, MAX_CORRIDOR_LVL);

        int originCellX = Math.floorDiv(minX, 1 << lvl);
        int originCellY = Math.floorDiv(minY, 1 << lvl);
        int originCellZ = Math.floorDiv(minZ, 1 << lvl);
        int gx = CorridorMath.cellsFor(spanX, lvl) + 1;
        int gy = CorridorMath.cellsFor(spanY, lvl) + 1;
        int gz = CorridorMath.cellsFor(spanZ, lvl) + 1;

        boolean[] opaque = VoxelCloud.fillOpaque(engine, colors,
                originCellX, originCellY, originCellZ, gx, gy, gz, lvl);

        CorridorFinder.Cell start = cell(p, originCellX, originCellY, originCellZ, lvl, gx, gy, gz);
        CorridorFinder.Cell goal = cell(t, originCellX, originCellY, originCellZ, lvl, gx, gy, gz);
        CorridorFinder.Result res = CorridorFinder.find(opaque, gx, gy, gz, start, goal, NODE_CAP);

        List<double[]> corridor = new ArrayList<>(res.path().size());
        for (CorridorFinder.Cell c : res.path()) {
            corridor.add(CorridorMath.cellCentreShifted(c.x(), c.y(), c.z(),
                    originCellX, originCellY, originCellZ, lvl));
        }
        return corridor;
    }

    // Narrow [lo,hi] to at most `cap` wide, always containing `keep` (the player) and extending
    // toward `toward` (the goal). Returns [lo,hi] unchanged when already within cap.
    private static int[] clipToward(int lo, int hi, int keep, int toward, int cap) {
        if (hi - lo <= cap) return new int[]{lo, hi};
        if (toward >= keep) return new int[]{keep, keep + cap};
        return new int[]{keep - cap, keep};
    }

    private static CorridorFinder.Cell cell(double[] shifted,
            int originCellX, int originCellY, int originCellZ, int lvl, int gx, int gy, int gz) {
        int cx = Math.floorDiv((int) Math.floor(shifted[0]), 1 << lvl) - originCellX;
        int cy = Math.floorDiv((int) Math.floor(shifted[1]), 1 << lvl) - originCellY;
        int cz = Math.floorDiv((int) Math.floor(shifted[2]), 1 << lvl) - originCellZ;
        return new CorridorFinder.Cell(
                Math.max(0, Math.min(gx - 1, cx)),
                Math.max(0, Math.min(gy - 1, cy)),
                Math.max(0, Math.min(gz - 1, cz)));
    }
}
