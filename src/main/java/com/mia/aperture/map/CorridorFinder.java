package com.mia.aperture.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// A* through a coarse grid, treating a cell as passable when it is NOT opaque — i.e. air
// connectivity, no gravity. 26-connected so the search can thread a shaft that steps diagonally.
// Returns the path (start..goal), the nearest-to-goal path (PARTIAL) when the goal is unreachable,
// or NO_ROUTE when the start itself is boxed in. Pure: no Voxy or Minecraft types.
public final class CorridorFinder {
    public record Cell(int x, int y, int z) {}
    public enum Status { FOUND, PARTIAL, NO_ROUTE }
    public record Result(List<Cell> path, Status status) {}

    private CorridorFinder() {}

    private static final int[][] DIRS = buildDirs();

    public static Result find(boolean[] opaque, int gx, int gy, int gz,
            Cell start, Cell goal, int nodeCap) {
        Cell s = nearestPassable(opaque, gx, gy, gz, start);
        if (s == null) return new Result(List.of(), Status.NO_ROUTE);
        Cell g = clamp(goal, gx, gy, gz);

        Map<Integer, Integer> cameFrom = new HashMap<>();
        Map<Integer, Double> gScore = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));
        int startKey = key(s.x(), s.y(), s.z(), gx, gz);
        gScore.put(startKey, 0.0);
        open.add(new double[]{startKey, heuristic(s, g)});

        int best = startKey;
        double bestH = heuristic(s, g);
        int expanded = 0;

        while (!open.isEmpty() && expanded < nodeCap) {
            double[] cur = open.poll();
            int ck = (int) cur[0];
            int cx = ck % gx, cy = ck / (gx * gz), cz = (ck / gx) % gz;
            if (cx == g.x() && cy == g.y() && cz == g.z()) {
                return new Result(reconstruct(cameFrom, ck, gx, gz), Status.FOUND);
            }
            expanded++;
            double cg = gScore.getOrDefault(ck, Double.MAX_VALUE);
            for (int[] d : DIRS) {
                int nx = cx + d[0], ny = cy + d[1], nz = cz + d[2];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= gx || ny >= gy || nz >= gz) continue;
                if (opaque[(ny * gz + nz) * gx + nx]) continue;
                double step = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                int nk = key(nx, ny, nz, gx, gz);
                double ng = cg + step;
                if (ng < gScore.getOrDefault(nk, Double.MAX_VALUE)) {
                    gScore.put(nk, ng);
                    cameFrom.put(nk, ck);
                    double h = heuristic(new Cell(nx, ny, nz), g);
                    if (h < bestH) { bestH = h; best = nk; }
                    open.add(new double[]{nk, ng + h});
                }
            }
        }
        return new Result(reconstruct(cameFrom, best, gx, gz), Status.PARTIAL);
    }

    private static Cell nearestPassable(boolean[] o, int gx, int gy, int gz, Cell c) {
        Cell k = clamp(c, gx, gy, gz);
        for (int r = 0; r <= 2; r++) {
            for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                int x = k.x() + dx, y = k.y() + dy, z = k.z() + dz;
                if (x < 0 || y < 0 || z < 0 || x >= gx || y >= gy || z >= gz) continue;
                if (!o[(y * gz + z) * gx + x]) return new Cell(x, y, z);
            }
        }
        return null;
    }

    private static Cell clamp(Cell c, int gx, int gy, int gz) {
        return new Cell(Math.max(0, Math.min(gx - 1, c.x())),
                        Math.max(0, Math.min(gy - 1, c.y())),
                        Math.max(0, Math.min(gz - 1, c.z())));
    }

    private static int key(int x, int y, int z, int gx, int gz) {
        return (y * gz + z) * gx + x;
    }

    private static double heuristic(Cell a, Cell b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<Cell> reconstruct(Map<Integer, Integer> cameFrom, int endKey, int gx, int gz) {
        List<Cell> path = new ArrayList<>();
        Integer k = endKey;
        while (k != null) {
            int x = k % gx, y = k / (gx * gz), z = (k / gx) % gz;
            path.add(new Cell(x, y, z));
            k = cameFrom.get(k);
        }
        Collections.reverse(path);
        return path;
    }

    private static int[][] buildDirs() {
        int[][] d = new int[26][];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dy != 0 || dz != 0) d[i++] = new int[]{dx, dy, dz};
        return d;
    }
}
