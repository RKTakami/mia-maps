package com.mia.aperture.map;

import java.util.ArrayList;
import java.util.List;

// Plans one descent "leg" through solid rock when open descent is blocked (an overhang):
// dig straight down, then a short horizontal break-out tunnel toward the goal. Pure, grid
// coordinates. Returns null when no safe dig leg fits the bounds (caller keeps the partial
// route rather than recommending a dig into a void).
public final class DescentPlanner {
    // entry = the frontier stand cell (shaft mouth); cells = blocks to mine, in order.
    public record Plan(int[] entry, List<int[]> cells) {}

    private DescentPlanner() {}

    public static Plan plan(TraversabilityGrid g, int fx, int fy, int fz,
                            int gx, int gy, int gz, int maxDig, int maxTunnel) {
        // Dominant horizontal direction toward the goal.
        int dirx, dirz;
        if (Math.abs(gx - fx) >= Math.abs(gz - fz)) {
            dirx = gx == fx ? 1 : Integer.signum(gx - fx);
            dirz = 0;
        } else {
            dirx = 0;
            dirz = gz == fz ? 1 : Integer.signum(gz - fz);
        }

        List<int[]> cells = new ArrayList<>();
        int x = fx, z = fz, y = fy;   // standing level y; floor at y-1
        boolean airBelow = false;
        int by = fy;                  // break-out level for the tunnel phase

        // Phase 1: dig straight down.
        for (int d = 0; d < maxDig; d++) {
            int floorY = y - 1;
            if (floorY < 1) break;                          // world bottom: solid progress
            if (!g.opaque(x, floorY, z)) { airBelow = true; by = y; break; } // overhang underside
            cells.add(new int[]{x, floorY, z});             // mine the floor block, descend one
            y = floorY;
            if (y <= gy) return new Plan(new int[]{fx, fy, fz}, cells);          // reached goal depth
            if (g.standable(x + dirx, y, z + dirz))                              // natural ledge
                return new Plan(new int[]{fx, fy, fz}, cells);
        }

        if (!airBelow) {
            // Dug through solid to the leg cap / bedrock, bottom still solid -> safe progress.
            return cells.isEmpty() ? null : new Plan(new int[]{fx, fy, fz}, cells);
        }

        // Phase 2: tunnel horizontally toward the goal at level `by`.
        for (int t = 1; t <= maxTunnel; t++) {
            int nx = x + dirx * t, nz = z + dirz * t;
            int floorY = by - 1;
            if (nx < 0 || nz < 0 || nx >= g.gx || nz >= g.gz) break;
            if (!g.opaque(nx, floorY, nz)) break;           // floor gone -> would fall; stop
            if (g.opaque(nx, by, nz)) cells.add(new int[]{nx, by, nz});
            if (by + 1 < g.gy && g.opaque(nx, by + 1, nz)) cells.add(new int[]{nx, by + 1, nz});
            int ex = nx + dirx, ez = nz + dirz;
            boolean inb = ex >= 0 && ez >= 0 && ex < g.gx && ez < g.gz;
            boolean openAhead = inb && !g.opaque(ex, by, ez) && !g.opaque(ex, by + 1, ez);
            boolean ledgeAhead = g.standable(ex, by, ez);
            if (openAhead || ledgeAhead)
                return new Plan(new int[]{fx, fy, fz}, cells);
        }
        // Air below with no safe break-out within bounds -> don't recommend digging into a void.
        return null;
    }
}
