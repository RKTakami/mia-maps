package com.mia.aperture.map;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.AbyssUtil;
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
    private static final int LVL = 0;          // finest LOD for accurate footing
    private static final int NODE_CAP = 200_000;
    private static final double REROUTE_DIST = 4.0;
    private static final double OFF_ROUTE_DIST = 3.5;      // strayed-from-path trigger
    private static final long REROUTE_COOLDOWN_MS = 250;   // max ~4 re-routes/sec
    private static final int MAX_DIG = 24;
    private static final int MAX_TUNNEL = 8;

    private static volatile Route route = Route.EMPTY;
    private static volatile double[] destination; // world x,y,z or null
    private static volatile double px, py, pz;     // latest player position
    private static volatile boolean needsRecompute;
    private static double lastX, lastY, lastZ;
    private static long lastRerouteMs;
    private static Thread thread;

    private RouteService() {}

    public static Route route() { return route; }
    public static double[] destination() { return destination; }
    public static boolean hasDestination() { return destination != null; }

    public static void setDestination(double wx, double wy, double wz) {
        destination = new double[]{wx, wy, wz};
        needsRecompute = true;
        ensureThread();
    }

    public static void clear() {
        destination = null;
        route = Route.EMPTY;
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
        ensureThread();
    }

    // The player has strayed from the route (e.g. knocked off, fell) if the nearest route point
    // is farther than OFF_ROUTE_DIST. No route yet -> not off-route.
    private static boolean offRoute(double x, double y, double z) {
        java.util.List<double[]> pts = route.points();
        if (pts.isEmpty()) return false;
        double best = Double.MAX_VALUE;
        for (double[] p : pts) {
            double dx = p[0] - x, dy = p[1] - y, dz = p[2] - z;
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
                if (needsRecompute && destination != null) {
                    needsRecompute = false;
                    route = compute(destination);
                } else {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                System.err.println("[MIA Maps] route compute failed: " + t);
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
        }
    }

    private static Route compute(double[] dst) {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        Minecraft mc = Minecraft.getInstance();
        if (rs == null || mc.player == null || dst == null) return Route.EMPTY;
        WorldEngine engine = rs.getEngine();
        MapColorSource colors = MapCompositor.colorSource();
        if (colors == null) return Route.EMPTY;

        double x = px, y = py, z = pz;
        int sector = AbyssUtil.getSection(x);
        int shiftX = sector << 14;
        int shiftYc = (240 - sector * 30) * 16;

        // Box centre: biased toward the destination but keeping the player >=24 blocks from the edge.
        double dx = dst[0] - x, dy = dst[1] - y, dz = dst[2] - z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double bias = Math.min(horiz * 0.5, BOX / 2.0 - 24);
        double ux = horiz > 1e-6 ? dx / horiz : 0, uz = horiz > 1e-6 ? dz / horiz : 0;
        double bcx = x + ux * bias, bcz = z + uz * bias;
        double bcy = y + Math.max(-(VBOX - 24), Math.min(VBOX - 24, dy * 0.5));

        int gx = BOX, gy = 2 * VBOX, gz = BOX;
        int originX = (int) Math.floor(bcx) - shiftX - gx / 2;
        int originY = (int) Math.floor(bcy) + shiftYc - gy / 2;
        int originZ = (int) Math.floor(bcz) - gz / 2;

        boolean[] opaque = VoxelCloud.fillOpaque(engine, colors, originX, originY, originZ, gx, gy, gz, LVL);
        TraversabilityGrid grid = new TraversabilityGrid(opaque, gx, gy, gz);

        Pathfinder.Cell start = nearestStandable(grid,
                (int) Math.floor(x) - shiftX - originX,
                (int) Math.floor(y) + shiftYc - originY,
                (int) Math.floor(z) - originZ);
        if (start == null) return Route.EMPTY;
        Pathfinder.Cell goal = clampCell(grid,
                (int) Math.floor(dst[0]) - shiftX - originX,
                (int) Math.floor(dst[1]) + shiftYc - originY,
                (int) Math.floor(dst[2]) - originZ);

        int safeDrop = com.mia.aperture.client.MiaApertureModClient.mapSettings.safeDropBlocks;
        Pathfinder.Params params = new Pathfinder.Params(1, safeDrop, 1);
        Pathfinder.Result res = Pathfinder.find(grid, start, goal, params, NODE_CAP);
        List<double[]> pts = new ArrayList<>(res.path().size());
        for (Pathfinder.Cell c : res.path()) {
            pts.add(cellToWorld(c.x(), c.y(), c.z(), originX, originY, originZ, shiftX, shiftYc));
        }

        Route.DigPlan digPlan = null;
        Pathfinder.Cell frontier = res.path().isEmpty()
                ? start : res.path().get(res.path().size() - 1);
        // Recommend digging when the route can't reach the goal and the closest we got (the
        // frontier, where the player will end up stuck) is still well above the goal — a real
        // descent the pathfinder couldn't finish, i.e. an overhang. Dig FROM the frontier.
        boolean descentRemains = frontier.y() > goal.y() + safeDrop;
        DescentPlanner.Plan dp = null;
        if (res.status() != Pathfinder.Status.FOUND && descentRemains) {
            dp = DescentPlanner.plan(grid,
                    frontier.x(), frontier.y(), frontier.z(),
                    goal.x(), goal.y(), goal.z(), MAX_DIG, MAX_TUNNEL);
            if (dp != null) {
                double[] entryW = cellToWorld(dp.entry()[0], dp.entry()[1], dp.entry()[2],
                        originX, originY, originZ, shiftX, shiftYc);
                List<double[]> cw = new ArrayList<>(dp.cells().size());
                for (int[] c : dp.cells()) {
                    cw.add(cellToWorld(c[0], c[1], c[2], originX, originY, originZ, shiftX, shiftYc));
                }
                digPlan = new Route.DigPlan(entryW, cw);
            }
        }
        return new Route(pts, List.of(), digPlan, res.status());
    }

    private static double[] cellToWorld(int cx, int cy, int cz,
            int originX, int originY, int originZ, int shiftX, int shiftYc) {
        return new double[]{
                (originX + cx) + shiftX + 0.5,
                (originY + cy) - shiftYc + 0.5,
                (originZ + cz) + 0.5};
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
