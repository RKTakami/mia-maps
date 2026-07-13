package com.mia.aperture.client;

import com.mia.aperture.map.BeaconGeometry;
import com.mia.aperture.map.Route;
import com.mia.aperture.map.RouteService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Highlights the active route + dig path as in-view block markers over the real game display:
// bright where the block has line of sight from the eye, dim/ghosted where terrain occludes it
// (hybrid). Screen-space projection (same camera math as BeaconRenderer) so it does not depend on
// the reworked world render pipeline; occlusion is a per-block eye raycast against real blocks.
public final class RoutePathRenderer {
    private static final int ROUTE_BRIGHT = 0xFF33DDFF, ROUTE_DIM = 0x5533DDFF;
    private static final int DIG_BRIGHT = 0xFFFFAA33, DIG_DIM = 0x55FFAA33;
    private static final int HALO = 0xC0000000;
    private static final double RENDER_RANGE = 72.0;
    private static final double OCCL_RANGE = 48.0;

    private RoutePathRenderer() {}

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Route rt = RouteService.route();
        Route.DigPlan dig = rt.dig();
        if (rt.points().isEmpty() && dig == null) return;

        Vec3 eye = mc.player.getEyePosition();
        double yaw = Math.toRadians(mc.player.getYRot());
        double pitch = Math.toRadians(mc.player.getXRot());
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double fx = -Math.sin(yaw) * cp, fy = -sp, fz = Math.cos(yaw) * cp;
        double rrx = -fz, rrz = fx;
        double rl = Math.sqrt(rrx * rrx + rrz * rrz);
        if (rl < 1e-6) rl = 1;
        double rx = rrx / rl, rz = rrz / rl;
        double lx = -rx, ly = 0, lz = -rz;
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
        int w = g.guiWidth(), h = g.guiHeight();
        double focal = (h / 2.0) / Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);

        for (double[] p : rt.points()) {
            drawCell(g, mc, eye, p[0], p[1], p[2], fx, fy, fz, ux, uy, uz, lx, ly, lz, focal, w, h,
                    ROUTE_BRIGHT, ROUTE_DIM);
        }
        if (dig != null) {
            for (double[] c : dig.cells()) {
                drawCell(g, mc, eye, c[0], c[1], c[2], fx, fy, fz, ux, uy, uz, lx, ly, lz, focal, w, h,
                        DIG_BRIGHT, DIG_DIM);
            }
        }
    }

    private static void drawCell(GuiGraphics g, Minecraft mc, Vec3 eye,
            double wx, double wy, double wz,
            double fx, double fy, double fz, double ux, double uy, double uz,
            double lx, double ly, double lz, double focal, int w, int h,
            int bright, int dim) {
        double relX = wx - eye.x, relY = wy - eye.y, relZ = wz - eye.z;
        double dist = Math.sqrt(relX * relX + relY * relY + relZ * relZ);
        if (dist > RENDER_RANGE || dist < 0.5) return;
        BeaconGeometry.Screen s = BeaconGeometry.project(relX, relY, relZ,
                fx, fy, fz, ux, uy, uz, lx, ly, lz, focal, w, h);
        if (!s.onScreen()) return;
        boolean occluded = dist <= OCCL_RANGE && occluded(mc, eye, wx, wy, wz, dist);
        int color = occluded ? dim : bright;
        int size = (int) Math.max(2, Math.min(7, 90.0 / (dist + 4.0)));
        if (!occluded) {
            g.fill(s.x() - size - 1, s.y() - size - 1, s.x() + size + 1, s.y() + size + 1, HALO);
        }
        g.fill(s.x() - size, s.y() - size, s.x() + size, s.y() + size, color);
    }

    private static boolean occluded(Minecraft mc, Vec3 eye, double wx, double wy, double wz, double dist) {
        Vec3 dir = new Vec3(wx - eye.x, wy - eye.y, wz - eye.z);
        Vec3 stop = eye.add(dir.scale((dist - 0.5) / dist));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, stop,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() != HitResult.Type.MISS;
    }
}
