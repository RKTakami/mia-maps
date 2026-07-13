package com.mia.aperture.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// A* over standable cells. Moves: walk / step up 1 / drop (<= maxFall) / jump a small air gap
// (<= maxJumpGap). Grid units. Returns the path (start..goal), or the nearest-to-goal path
// (PARTIAL) when the goal isn't reachable, or NO_ROUTE if boxed in / the start isn't standable.
public final class Pathfinder {
    public record Params(int stepUp, int maxFall, int maxJumpGap) {}
    public record Cell(int x, int y, int z) {}
    public enum Status { FOUND, PARTIAL, NO_ROUTE }
    public record Result(List<Cell> path, Status status) {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private Pathfinder() {}

    public static Result find(TraversabilityGrid g, Cell start, Cell goal, Params p, int nodeCap) {
        if (!g.standable(start.x(), start.y(), start.z())) return new Result(List.of(), Status.NO_ROUTE);

        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Double> gScore = new HashMap<>();
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[1]), Double.longBitsToDouble(b[1])));
        long startKey = key(g, start.x(), start.y(), start.z());
        gScore.put(startKey, 0.0);
        open.add(new long[]{startKey, Double.doubleToLongBits(heur(start, goal))});

        long bestKey = startKey;
        double bestH = heur(start, goal);
        int expanded = 0;

        while (!open.isEmpty() && expanded++ < nodeCap) {
            long ck = open.poll()[0];
            int[] c = unkey(g, ck);
            if (c[0] == goal.x() && c[1] == goal.y() && c[2] == goal.z())
                return new Result(reconstruct(g, cameFrom, ck), Status.FOUND);
            double h = heur(new Cell(c[0], c[1], c[2]), goal);
            if (h < bestH) { bestH = h; bestKey = ck; }

            for (double[] nb : neighbors(g, c[0], c[1], c[2], p)) {
                int nx = (int) nb[0], ny = (int) nb[1], nz = (int) nb[2];
                long nk = key(g, nx, ny, nz);
                double tentative = gScore.get(ck) + nb[3];
                if (tentative < gScore.getOrDefault(nk, Double.MAX_VALUE)) {
                    cameFrom.put(nk, ck);
                    gScore.put(nk, tentative);
                    double f = tentative + heur(new Cell(nx, ny, nz), goal);
                    open.add(new long[]{nk, Double.doubleToLongBits(f)});
                }
            }
        }
        List<Cell> partial = reconstruct(g, cameFrom, bestKey);
        return new Result(partial, partial.size() > 1 ? Status.PARTIAL : Status.NO_ROUTE);
    }

    // neighbours as {x, y, z, cost}
    private static List<double[]> neighbors(TraversabilityGrid g, int x, int y, int z, Params p) {
        List<double[]> out = new ArrayList<>();
        for (int[] d : DIRS) {
            int nx = x + d[0], nz = z + d[1];
            boolean stepped = false;
            // walk / step up 1 / drop: highest standable in the adjacent column within [y+stepUp, y-maxFall]
            for (int ny = y + p.stepUp(); ny >= y - p.maxFall(); ny--) {
                if (g.standable(nx, ny, nz)) {
                    // The transit must be clear: stepping off the edge and falling to the landing
                    // can't pass through solid rock (else it's an unreachable pocket under an
                    // overhang). Require the column (nx,nz) from just above the landing up to the
                    // player's head level to be all air.
                    if (clear(g, nx, nz, ny + 1, y + 1)) {
                        double cost = 1.0 + (ny < y ? (y - ny) * 0.5 : 0) + (ny > y ? 0.5 : 0);
                        out.add(new double[]{nx, ny, nz, cost});
                        stepped = true;
                    }
                    break;
                }
            }
            // jump a small air gap (only if the immediate step failed -> a gap)
            if (!stepped) {
                for (int gap = 1; gap <= p.maxJumpGap(); gap++) {
                    int jx = x + d[0] * (gap + 1), jz = z + d[1] * (gap + 1);
                    for (int jy = y; jy >= y - 2; jy--) {
                        if (g.standable(jx, jy, jz)) {
                            out.add(new double[]{jx, jy, jz, 2.0 + gap});
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    // All cells in column (x,z) from lo..hi (inclusive) are non-opaque (air). Empty range = clear.
    private static boolean clear(TraversabilityGrid g, int x, int z, int lo, int hi) {
        for (int yy = lo; yy <= hi; yy++) if (g.opaque(x, yy, z)) return false;
        return true;
    }

    private static double heur(Cell a, Cell b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    private static long key(TraversabilityGrid g, int x, int y, int z) {
        return ((long) y * g.gz + z) * g.gx + x;
    }
    private static int[] unkey(TraversabilityGrid g, long k) {
        int x = (int) (k % g.gx); long r = k / g.gx;
        int z = (int) (r % g.gz); int y = (int) (r / g.gz);
        return new int[]{x, y, z};
    }
    private static List<Cell> reconstruct(TraversabilityGrid g, Map<Long, Long> cameFrom, long end) {
        List<Cell> path = new ArrayList<>();
        Long k = end;
        while (k != null) {
            int[] c = unkey(g, k);
            path.add(new Cell(c[0], c[1], c[2]));
            k = cameFrom.get(k);
        }
        java.util.Collections.reverse(path);
        return path;
    }
}
