package com.mia.aperture.map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import me.cortex.voxy.client.core.util.AbyssUtil;
import com.mia.aperture.state.AbyssMapState;

public final class MinimapRenderer {
    // Electric red for the player marker — not present in the MIA terrain palette and
    // distinct from the waypoint RED preset, so "you" always stands out.
    public static final int PLAYER_COLOR = 0xFFFF0055;
    // Cyan route trail, matching the 3D orbit view's route line.
    public static final int ROUTE_COLOR = 0xFF33DDFF;
    // Amber "dig here" marker for the Plan-B descent recommendation.
    public static final int DIG_COLOR = 0xFFFFAA33;

    private static void drawDownTriangle(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 3, y - 3, x + 4, y - 2, color);
        g.fill(x - 2, y - 2, x + 3, y - 1, color);
        g.fill(x - 1, y - 1, x + 2, y,     color);
        g.fill(x,     y,     x + 1, y + 1, color);
    }

    private MinimapRenderer() {}

    // Draws the minimap frame + map + crosshair + arrow + cardinals at (x,y), size px.
    public static void draw(GuiGraphics ctx, LocalPlayer player, int x, int y, int size, MapSettings s) {
        int sector = AbyssUtil.getSection(player.getX());
        boolean caveActive = CaveDetector.caveActive(s.caveMode, AbyssMapState.caveEnclosed);
        int bandTop = AbyssMapState.mapBandTopShifted((int) player.getY(), sector,
                AbyssMapState.mapDepthActive, AbyssMapState.scrollTargetCenterY);
        MapMode mode = caveActive ? MapMode.CAVE : AbyssMapState.mapRenderMode;
        boolean round = s.shape == MapSettings.FrameShape.ROUND;
        MapCompositor.composeHud(player.getX(), player.getZ(), bandTop, bandTop - AbyssMapState.bandHeight(),
                mode, round);

        int cx = x + size / 2;
        int cy = y + size / 2;
        int radius = size / 2;
        float yaw = player.getYRot();

        if (!round) {
            MinimapFrame.drawSquareFrame(ctx, x, y, size);
        }

        ctx.enableScissor(x, y, x + size, y + size);
        ctx.pose().pushMatrix();
        ctx.pose().translate(cx + 0.5f, cy + 0.5f);
        float rot = MinimapMarkers.headingRotationRad(s.orientation, yaw);
        if (rot != 0f) ctx.pose().rotate(rot);
        int drawSize = (int) (size * MapCompositor.OVERSAMPLE);
        int half = drawSize / 2;
        ctx.blit(MapCompositor.HUD_TEXTURE, -half, -half, half, half, 0.0f, 1.0f, 0.0f, 1.0f);
        ctx.pose().popMatrix();
        ctx.disableScissor();

        if (round) {
            MinimapFrame.drawRoundBorder(ctx, x, y, size);
        }

        ctx.fill(cx - 3, cy, cx + 4, cy + 1, 0xAAFF0055);
        ctx.fill(cx, cy - 3, cx + 1, cy + 4, 0xAAFF0055);

        ctx.pose().pushMatrix();
        ctx.pose().translate(cx + 0.5f, cy + 0.5f);
        if (s.orientation == MapSettings.Orientation.NORTH_UP) {
            ctx.pose().rotate((float) Math.toRadians(yaw + 180.0f));
        }
        ctx.fill(0, -6, 1, -4, PLAYER_COLOR);   // slender tip
        ctx.fill(-1, -4, 2, -2, PLAYER_COLOR);
        ctx.fill(-2, -2, 3, 0, PLAYER_COLOR);
        ctx.fill(-3, 0, -1, 2, PLAYER_COLOR);   // left wing (notched base)
        ctx.fill(1, 0, 3, 2, PLAYER_COLOR);     // right wing
        ctx.pose().popMatrix();

        String wpKey = com.mia.aperture.map.WaypointStore.currentServerKey(net.minecraft.client.Minecraft.getInstance());
        double halfBlocks = MapCompositor.HUD_RADIUS_BLOCKS;
        float wpRot = MinimapMarkers.headingRotationRad(s.orientation, yaw);

        java.util.List<double[]> route = RouteService.route().points();
        for (double[] rp : route) {
            double dx = rp[0] - player.getX();
            double dz = rp[2] - player.getZ();
            if (Math.abs(dx) > halfBlocks || Math.abs(dz) > halfBlocks) continue;
            float bx = (float) (dx / halfBlocks) * radius;
            float bz = (float) (dz / halfBlocks) * radius;
            float rx = (float) (bx * Math.cos(wpRot) - bz * Math.sin(wpRot));
            float rz = (float) (bx * Math.sin(wpRot) + bz * Math.cos(wpRot));
            int px = cx + Math.round(rx);
            int py = cy + Math.round(rz);
            ctx.fill(px - 1, py - 1, px + 1, py + 1, ROUTE_COLOR);
        }

        Route.DigPlan dig = RouteService.route().dig();
        if (dig != null) {
            double dgx = dig.entry()[0] - player.getX();
            double dgz = dig.entry()[2] - player.getZ();
            if (Math.abs(dgx) <= halfBlocks && Math.abs(dgz) <= halfBlocks) {
                float bx = (float) (dgx / halfBlocks) * radius;
                float bz = (float) (dgz / halfBlocks) * radius;
                float rx = (float) (bx * Math.cos(wpRot) - bz * Math.sin(wpRot));
                float rz = (float) (bx * Math.sin(wpRot) + bz * Math.cos(wpRot));
                drawDownTriangle(ctx, cx + Math.round(rx), cy + Math.round(rz), DIG_COLOR);
            }
        }

        for (com.mia.aperture.map.Waypoint w : s.showNavMarkers
                ? com.mia.aperture.client.MiaApertureModClient.waypoints.list(wpKey)
                : java.util.List.<com.mia.aperture.map.Waypoint>of()) {
            if (!w.visible) continue;
            double dx = w.x - player.getX();
            double dz = w.z - player.getZ();
            if (Math.abs(dx) > halfBlocks || Math.abs(dz) > halfBlocks) continue;
            float bx = (float) (dx / halfBlocks) * radius;
            float bz = (float) (dz / halfBlocks) * radius;
            float rx = (float) (bx * Math.cos(wpRot) - bz * Math.sin(wpRot));
            float rz = (float) (bx * Math.sin(wpRot) + bz * Math.cos(wpRot));
            int dotX = cx + Math.round(rx);
            int dotY = cy + Math.round(rz);
            ctx.fill(dotX - 1, dotY - 1, dotX + 2, dotY + 2, w.color.argb());
        }

        java.util.List<com.mia.aperture.client.MobTracker.Blip> blips =
                com.mia.aperture.client.MobTracker.collect(
                        net.minecraft.client.Minecraft.getInstance(), halfBlocks, MOB_BAND, s);
        var mcFont = net.minecraft.client.Minecraft.getInstance().font;
        int labeled = 0;
        for (com.mia.aperture.client.MobTracker.Blip bl : blips) {
            double dx = bl.x() - player.getX();
            double dz = bl.z() - player.getZ();
            if (Math.abs(dx) > halfBlocks || Math.abs(dz) > halfBlocks) continue;
            float bx = (float) (dx / halfBlocks) * radius;
            float bz = (float) (dz / halfBlocks) * radius;
            float rx = (float) (bx * Math.cos(wpRot) - bz * Math.sin(wpRot));
            float rz = (float) (bx * Math.sin(wpRot) + bz * Math.cos(wpRot));
            int mx = cx + Math.round(rx), my = cy + Math.round(rz);
            double dy = bl.y() - player.getY();
            int fade = (int) Math.min(150, Math.abs(dy) / MOB_BAND * 150);
            int color = (bl.cat().color & 0xFFFFFF) | ((0xFF - fade) << 24);
            ctx.fill(mx - 1, my - 1, mx + 2, my + 2, color);
            if (dy > 2) mobUp(ctx, mx, my, color);
            else if (dy < -2) mobDown(ctx, mx, my, color);
            if (s.mobLabels && labeled < 3) {
                ctx.drawString(mcFont, bl.name(), mx + 5, my - 4, 0xFFFFFFFF);
                labeled++;
            }
        }

        MinimapFrame.drawCardinals(ctx, cx, cy, radius - 6, s.orientation, yaw);
    }

    public static final double MOB_BAND = 32.0;

    private static void mobUp(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y - 2, x + 1, y - 1, color);
        g.fill(x - 1, y - 1, x + 2, y, color);
    }
    private static void mobDown(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 1, y + 1, x + 2, y + 2, color);
        g.fill(x, y + 2, x + 1, y + 3, color);
    }
}
