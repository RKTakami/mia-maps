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
    private static final Pathfinder.Params PARAMS = new Pathfinder.Params(1, 3, 1);

    private static volatile Route route = Route.EMPTY;
    private static volatile double[] destination; // world x,y,z or null
    private static volatile double px, py, pz;     // latest player position
    private static volatile boolean needsRecompute;
    private static double lastX, lastY, lastZ;
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

    // Call each client tick with the player position; triggers a re-route when it moves enough.
    public static void tick(double x, double y, double z) {
        px = x; py = y; pz = z;
        if (destination == null) return;
        if (Math.abs(x - lastX) + Math.abs(y - lastY) + Math.abs(z - lastZ) > REROUTE_DIST) {
            lastX = x; lastY = y; lastZ = z;
            needsRecompute = true;
        }
        ensureThread();
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

        Pathfinder.Result res = Pathfinder.find(grid, start, goal, PARAMS, NODE_CAP);
        List<double[]> pts = new ArrayList<>(res.path().size());
        for (Pathfinder.Cell c : res.path()) {
            pts.add(new double[]{
                    (originX + c.x()) + shiftX + 0.5,
                    (originY + c.y()) - shiftYc + 0.5,
                    (originZ + c.z()) + 0.5});
        }
        return new Route(pts, List.of(), res.status());
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
