package com.mia.aperture.map;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

// Computes a walkable route from the player to the active destination waypoint over Voxy data,
// on a background thread. Local + progressive: the search box is biased toward the destination
// but always contains the player, so distant goals yield a PARTIAL route that extends as you walk.
public final class RouteService {
    private static final int BOX = 192;        // horizontal grid edge (blocks) at lvl 0
    private static final int VBOX = 96;        // vertical grid extent (blocks) each way
    private static final int MARGIN = 24;      // keep the player this far from the box edge
    private static final int LVL = 0;          // finest LOD for accurate footing
    private static final int NODE_CAP = 200_000;
    private static final double REROUTE_DIST = 4.0;
    private static final double OFF_ROUTE_DIST = 3.5;      // strayed-from-path trigger
    private static final long REROUTE_COOLDOWN_MS = 250;   // max ~4 re-routes/sec
    private static final int MAX_DIG = 24;
    private static final int MAX_TUNNEL = 8;
    private static final double CORRIDOR_REFRESH_DIST = 64.0;  // recompute corridor after this much drift
    private static final double SUBGOAL_REACH = BOX / 2.0 - MARGIN;

    private static volatile Route route = Route.EMPTY;
    private static volatile java.util.List<double[]> corridor = java.util.List.of(); // shifted points
    private static volatile boolean needsCorridor;
    private static double lastCorridorX, lastCorridorY, lastCorridorZ;
    private static volatile double[] destination; // world x,y,z or null
    private static volatile double px, py, pz;     // latest player position
    private static volatile boolean needsRecompute;
    private static double lastX, lastY, lastZ;
    private static long lastRerouteMs;
    private static Thread thread;

    private RouteService() {}

    public static Route route() { return route; }

    // Route breadcrumbs still ahead of the player (passed ones erased), in the shifted column —
    // every layer. The 3D view wants these: it projects the shifted column directly.
    public static java.util.List<double[]> aheadPointsShifted() {
        double[] p = MapGeometry.toShiftedColumn(px, py, pz);
        return route.ahead(p[0], p[1], p[2]);
    }

    // Breadcrumbs still ahead that are on the player's CURRENT layer, in world coords. The in-world
    // overlay and the 2D map can only draw this layer, so points on other layers are dropped rather
    // than un-shifted into a place that does not exist in this section.
    public static java.util.List<double[]> aheadPointsWorld() {
        int sector = MapGeometry.sectorForX(px);
        java.util.List<double[]> out = new java.util.ArrayList<>();
        for (double[] s : aheadPointsShifted()) {
            if (MapGeometry.sectorForShiftedY(s[1], sector) != sector) continue;
            out.add(MapGeometry.toWorld(s[0], s[1], s[2], sector));
        }
        return out;
    }

    // The full coarse corridor (shifted column, player -> destination) for the 3D view to draw.
    // Empty when there is no destination or nothing could be sampled.
    public static java.util.List<double[]> corridorShifted() {
        return corridor;
    }

    // The dig plan in world coords, or null when there is none or it is not on the player's layer.
    public static Route.DigPlan digWorld() {
        Route.DigPlan d = route.dig();
        if (d == null) return null;
        int sector = MapGeometry.sectorForX(px);
        if (MapGeometry.sectorForShiftedY(d.entry()[1], sector) != sector) return null;
        double[] entry = MapGeometry.toWorld(d.entry()[0], d.entry()[1], d.entry()[2], sector);
        java.util.List<double[]> cells = new java.util.ArrayList<>(d.cells().size());
        for (double[] c : d.cells()) {
            cells.add(MapGeometry.toWorld(c[0], c[1], c[2], sector));
        }
        return new Route.DigPlan(entry, cells);
    }
    public static double[] destination() { return destination; }
    public static boolean hasDestination() { return destination != null; }

    public static void setDestination(double wx, double wy, double wz) {
        destination = new double[]{wx, wy, wz};
        needsRecompute = true;
        needsCorridor = true;
        ensureThread();
    }

    public static void clear() {
        destination = null;
        route = Route.EMPTY;
        corridor = java.util.List.of();
    }

    // Call each client tick with the player position; re-routes when the player has travelled
    // far enough OR has been knocked off the current path (deviation), throttled to avoid churn.
    public static void tick(double x, double y, double z) {
        px = x; py = y; pz = z;
        if (destination == null) return;
        boolean moved = Math.abs(x - lastX) + Math.abs(y - lastY) + Math.abs(z - lastZ) > REROUTE_DIST;
        boolean knockedOff = offRoute(x, y, z);
        long now = System.currentTimeMillis();
        if ((moved || knockedOff) && now - lastRerouteMs > REROUTE_COOLDOWN_MS) {
            lastX = x; lastY = y; lastZ = z;
            lastRerouteMs = now;
            needsRecompute = true;
        }
        if (Math.abs(x - lastCorridorX) + Math.abs(y - lastCorridorY) + Math.abs(z - lastCorridorZ)
                > CORRIDOR_REFRESH_DIST) {
            needsCorridor = true;
        }
        ensureThread();
    }

    // The player has strayed from the route (e.g. knocked off, fell) if the nearest route point
    // is farther than OFF_ROUTE_DIST. No route yet -> not off-route.
    private static boolean offRoute(double x, double y, double z) {
        java.util.List<double[]> pts = route.points();
        if (pts.isEmpty()) return false;
        // Route points are shifted; the caller hands us world coords. Comparing the two spaces
        // directly would read as "16384 blocks off route" the moment a route crossed a layer.
        double[] p = MapGeometry.toShiftedColumn(x, y, z);
        double best = Double.MAX_VALUE;
        for (double[] q : pts) {
            double dx = q[0] - p[0], dy = q[1] - p[1], dz = q[2] - p[2];
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) best = d;
        }
        return best > OFF_ROUTE_DIST * OFF_ROUTE_DIST;
    }

    private static synchronized void ensureThread() {
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(RouteService::loop, "MIA-Route-Worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        thread.start();
    }

    private static void loop() {
        while (true) {
            try {
                boolean did = false;
                if (needsCorridor && destination != null) {
                    needsCorridor = false;
                    lastCorridorX = px; lastCorridorY = py; lastCorridorZ = pz;
                    corridor = computeCorridor(destination);
                    did = true;
                }
                if (needsRecompute && destination != null) {
                    needsRecompute = false;
                    route = compute(destination);
                    did = true;
                }
                if (!did) Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                System.err.println("[MIA Maps] route compute failed: " + t);
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
        }
    }

    private static java.util.List<double[]> computeCorridor(double[] dst) {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        Minecraft mc = Minecraft.getInstance();
        if (rs == null || mc.player == null || dst == null) return java.util.List.of();
        MapColorSource colors = MapCompositor.colorSource();
        if (colors == null) return java.util.List.of();
        double[] p = MapGeometry.toShiftedColumn(px, py, pz);
        double[] t = MapGeometry.toShiftedColumn(dst[0], dst[1], dst[2]);
        return CorridorPlanner.plan(rs.getEngine(), colors, p, t);
    }

    private static Route compute(double[] dst) {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        Minecraft mc = Minecraft.getInstance();
        if (rs == null || mc.player == null || dst == null) return Route.EMPTY;
        WorldEngine engine = rs.getEngine();
        MapColorSource colors = MapCompositor.colorSource();
        if (colors == null) return Route.EMPTY;

        // Everything below is in the shifted column, where the sections stack into one continuous
        // space and a path may legitimately cross a layer boundary. Each endpoint converts through
        // its OWN section: the destination is frequently on a different layer than the player, and
        // borrowing the player's section there is what made off-layer routing nonsense.
        double[] p = MapGeometry.toShiftedColumn(px, py, pz);
        // Aim at the next point along the coarse corridor, not the raw destination. The corridor
        // threads the open shaft the whole way down; the block-accurate search below only has to
        // reach the next corridor point, which is within a box of the player. Falls back to the
        // destination itself when there is no corridor (e.g. it is still being built).
        double[] sub = CorridorMath.subGoal(corridor, p[0], p[1], p[2], SUBGOAL_REACH);
        double[] t = sub != null ? sub : MapGeometry.toShiftedColumn(dst[0], dst[1], dst[2]);
        RouteBox.Box b = RouteBox.place(p[0], p[1], p[2], t[0], t[1], t[2], BOX, VBOX, MARGIN);

        boolean[] opaque = VoxelCloud.fillOpaque(engine, colors,
                b.originX(), b.originY(), b.originZ(), b.gx(), b.gy(), b.gz(), LVL);
        TraversabilityGrid grid = new TraversabilityGrid(opaque, b.gx(), b.gy(), b.gz());

        Pathfinder.Cell start = nearestStandable(grid,
                (int) Math.floor(p[0]) - b.originX(),
                (int) Math.floor(p[1]) - b.originY(),
                (int) Math.floor(p[2]) - b.originZ());
        if (start == null) return Route.EMPTY;

        int goalRawX = (int) Math.floor(t[0]) - b.originX();
        int goalRawY = (int) Math.floor(t[1]) - b.originY();
        int goalRawZ = (int) Math.floor(t[2]) - b.originZ();
        // The destination is out of range when it falls outside the box: the goal below is then a
        // clamped placeholder on a box edge, not the real target. The route to it is still a useful
        // progressive step toward the destination, but a dig plan toward a placeholder would be a
        // tunnel to nowhere, so it is suppressed.
        boolean goalInBox = goalRawX >= 0 && goalRawX < b.gx()
                && goalRawY >= 0 && goalRawY < b.gy()
                && goalRawZ >= 0 && goalRawZ < b.gz();
        Pathfinder.Cell goal = clampCell(grid, goalRawX, goalRawY, goalRawZ);

        int safeDrop = com.mia.aperture.client.MiaApertureModClient.mapSettings.safeDropBlocks;
        Pathfinder.Params params = new Pathfinder.Params(1, safeDrop, 1);
        Pathfinder.Result res = Pathfinder.find(grid, start, goal, params, NODE_CAP);
        List<double[]> pts = new ArrayList<>(res.path().size());
        for (Pathfinder.Cell c : res.path()) {
            pts.add(cellToShifted(c.x(), c.y(), c.z(), b));
        }

        Route.DigPlan digPlan = null;
        Pathfinder.Cell frontier = res.path().isEmpty()
                ? start : res.path().get(res.path().size() - 1);
        // Recommend digging when the route can't reach an IN-BOX goal and the closest we got (the
        // frontier, where the player will end up stuck) is still well above the goal — a real
        // descent the pathfinder couldn't finish, i.e. an overhang. Dig FROM the frontier.
        boolean descentRemains = goalInBox && frontier.y() > goal.y() + safeDrop;
        if (res.status() != Pathfinder.Status.FOUND && descentRemains) {
            DescentPlanner.Plan dp = DescentPlanner.plan(grid,
                    frontier.x(), frontier.y(), frontier.z(),
                    goal.x(), goal.y(), goal.z(), MAX_DIG, MAX_TUNNEL);
            if (dp != null) {
                double[] entryS = cellToShifted(dp.entry()[0], dp.entry()[1], dp.entry()[2], b);
                List<double[]> cells = new ArrayList<>(dp.cells().size());
                for (int[] c : dp.cells()) {
                    cells.add(cellToShifted(c[0], c[1], c[2], b));
                }
                digPlan = new Route.DigPlan(entryS, cells);
            }
        }
        return new Route(pts, List.of(), digPlan, res.status());
    }

    private static double[] cellToShifted(int cx, int cy, int cz, RouteBox.Box b) {
        return new double[]{
                (b.originX() + cx) + 0.5,
                (b.originY() + cy) + 0.5,
                (b.originZ() + cz) + 0.5};
    }

    private static Pathfinder.Cell nearestStandable(TraversabilityGrid g, int x, int y, int z) {
        for (int r = 0; r <= 4; r++) {
            for (int dy = -r; dy <= r; dy++) {
                if (g.standable(x, y + dy, z)) return new Pathfinder.Cell(x, y + dy, z);
            }
        }
        return null;
    }

    private static Pathfinder.Cell clampCell(TraversabilityGrid g, int x, int y, int z) {
        x = Math.max(0, Math.min(g.gx - 1, x));
        y = Math.max(1, Math.min(g.gy - 1, y));
        z = Math.max(0, Math.min(g.gz - 1, z));
        return new Pathfinder.Cell(x, y, z);
    }
}
