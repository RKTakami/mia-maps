package com.mia.aperture.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// Bridges a stalled descent to the next point where natural descent resumes, by digging a short
// staircase and/or horizontal tunnel from the frontier. A bounded Dijkstra over stand-cells
// reached by mining (cost = blocks mined); returns the cheapest bridge or null when no resume
// point is reachable within budget. Pure: grid coords only, no Voxy/Minecraft.
public final class BridgePlanner {
    // entry = frontier stand cell; cells = blocks to mine, in dig order.
    public record Plan(int[] entry, List<int[]> cells) {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private BridgePlanner() {}

    public static Plan plan(TraversabilityGrid g, int fx, int fy, int fz,
                            int goalX, int goalY, int goalZ,
                            int safeFall, int survivableFall, int maxDig) {
        int frontierHoriz = Math.abs(fx - goalX) + Math.abs(fz - goalZ);
        long startKey = key(g, fx, fy, fz);
        Map<Long, Integer> dig = new HashMap<>();
        Map<Long, Long> from = new HashMap<>();
        Map<Long, int[][]> edge = new HashMap<>();
        dig.put(startKey, 0);
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        open.add(new long[]{startKey, 0});

        while (!open.isEmpty()) {
            long[] cur = open.poll();
            long ck = cur[0];
            int cd = (int) cur[1];
            if (cd > dig.getOrDefault(ck, Integer.MAX_VALUE)) continue;
            int[] c = unkey(g, ck);
            int x = c[0], y = c[1], z = c[2];

            // Resume where natural descent picks back up. Not the frontier itself, no farther from
            // the goal horizontally (never wander outward), and a real descent available. Same-level
            // resumes are valid: tunnelling through a wall to a shaft edge you then drop off is the
            // whole point — A* stalled precisely because it could not reach that edge by walking.
            if (ck != startKey
                    && Math.abs(x - goalX) + Math.abs(z - goalZ) <= frontierHoriz
                    && resumes(g, x, y, z, goalX, goalZ, survivableFall)) {
                return build(g, from, edge, ck, fx, fy, fz);
            }

            for (int[] d : DIRS) {
                relax(g, open, dig, from, edge, ck, cd, x + d[0], y, z + d[1], maxDig);       // tunnel
                relax(g, open, dig, from, edge, ck, cd, x + d[0], y - 1, z + d[1], maxDig);   // stair-down
            }
        }
        return null;
    }

    // Move to stand-cell (nx,ny,nz) by mining. Requires solid floor below the target (else it is a
    // drop, handled by resumes(), not a dug move). Mines the target's feet + head if solid.
    private static void relax(TraversabilityGrid g, PriorityQueue<long[]> open,
            Map<Long, Integer> dig, Map<Long, Long> from, Map<Long, int[][]> edge,
            long ck, int cd, int nx, int ny, int nz, int maxDig) {
        if (nx < 0 || ny < 1 || nz < 0 || nx >= g.gx || ny >= g.gy || nz >= g.gz) return;
        if (!g.opaque(nx, ny - 1, nz)) return;
        List<int[]> mined = new ArrayList<>(2);
        if (g.opaque(nx, ny, nz)) mined.add(new int[]{nx, ny, nz});
        if (ny + 1 < g.gy && g.opaque(nx, ny + 1, nz)) mined.add(new int[]{nx, ny + 1, nz});
        int nd = cd + mined.size();
        if (nd > maxDig) return;
        long nk = key(g, nx, ny, nz);
        if (nd < dig.getOrDefault(nk, Integer.MAX_VALUE)) {
            dig.put(nk, nd);
            from.put(nk, ck);
            edge.put(nk, mined.toArray(new int[0][]));
            open.add(new long[]{nk, nd});
        }
    }

    // Natural descent resumes at (x,y,z) if a survivable open drop lands standable directly below,
    // or stepping off toward the goal drops/steps survivably onto standable ground — the moves the
    // main A* would take next.
    private static boolean resumes(TraversabilityGrid g, int x, int y, int z,
            int goalX, int goalZ, int survivableFall) {
        if (dropDown(g, x, y, z, survivableFall)) return true;
        int dx = Integer.signum(goalX - x), dz = Integer.signum(goalZ - z);
        if (dx != 0 && stepOff(g, x + dx, z, y, survivableFall)) return true;
        if (dz != 0 && stepOff(g, x, z + dz, y, survivableFall)) return true;
        return false;
    }

    // A clear drop straight down at (x,z) landing standable within survivableFall.
    private static boolean dropDown(TraversabilityGrid g, int x, int y, int z, int survivableFall) {
        for (int yy = y - 1; yy >= Math.max(1, y - survivableFall); yy--) {
            if (g.opaque(x, yy, z)) break;
            if (g.standable(x, yy, z)) return true;
        }
        return false;
    }

    // Step off stand-level y into column (cx,cz): land standable within [y-survivableFall .. y]
    // with the fall column clear from the landing up to head level.
    private static boolean stepOff(TraversabilityGrid g, int cx, int cz, int y, int survivableFall) {
        if (cx < 0 || cz < 0 || cx >= g.gx || cz >= g.gz) return false;
        for (int yy = y; yy >= Math.max(1, y - survivableFall); yy--) {
            if (g.standable(cx, yy, cz)) {
                for (int k = yy + 1; k <= y + 1 && k < g.gy; k++) if (g.opaque(cx, k, cz)) return false;
                return true;
            }
        }
        return false;
    }

    private static Plan build(TraversabilityGrid g, Map<Long, Long> from, Map<Long, int[][]> edge,
            long endKey, int fx, int fy, int fz) {
        List<int[]> cells = new ArrayList<>();
        Long k = endKey;
        while (from.containsKey(k)) {
            for (int[] m : edge.get(k)) cells.add(m);
            k = from.get(k);
        }
        return new Plan(new int[]{fx, fy, fz}, cells);
    }

    private static long key(TraversabilityGrid g, int x, int y, int z) {
        return ((long) y * g.gz + z) * g.gx + x;
    }
    private static int[] unkey(TraversabilityGrid g, long kk) {
        int x = (int) (kk % g.gx); long r = kk / g.gx;
        int z = (int) (r % g.gz); int y = (int) (r / g.gz);
        return new int[]{x, y, z};
    }
}
