package com.mia.aperture.map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import me.cortex.voxy.client.core.util.AbyssUtil;
import com.mia.aperture.state.AbyssMapState;

public final class MinimapRenderer {
    private MinimapRenderer() {}

    // Draws the minimap frame + map + crosshair + arrow + cardinals at (x,y), size px.
    public static void draw(GuiGraphics ctx, LocalPlayer player, int x, int y, int size, MapSettings s) {
        int sector = AbyssUtil.getSection(player.getX());
        boolean caveActive = CaveDetector.caveActive(s.caveMode, AbyssMapState.caveEnclosed);
        int bandTop = AbyssMapState.effectiveBandTop((int) player.getY(), sector, caveActive,
                AbyssMapState.mapDepthActive, AbyssMapState.scrollTargetCenterY,
                AbyssMapState.caveRoofFound, AbyssMapState.caveRoofWorldY);
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

        ctx.fill(cx - 3, cy, cx + 4, cy + 1, 0x88FF0000);
        ctx.fill(cx, cy - 3, cx + 1, cy + 4, 0x88FF0000);

        ctx.pose().pushMatrix();
        ctx.pose().translate(cx + 0.5f, cy + 0.5f);
        if (s.orientation == MapSettings.Orientation.NORTH_UP) {
            ctx.pose().rotate((float) Math.toRadians(yaw + 180.0f));
        }
        ctx.fill(0, -4, 1, -3, 0xFFFFFF00);
        ctx.fill(-1, -3, 2, -2, 0xFFFFFF00);
        ctx.fill(-2, -2, 3, -1, 0xFFFFFF00);
        ctx.fill(-3, -1, 4, 0, 0xFFFFFF00);
        ctx.fill(-3, 0, -1, 1, 0xFFFFFF00);
        ctx.fill(2, 0, 4, 1, 0xFFFFFF00);
        ctx.pose().popMatrix();

        MinimapFrame.drawCardinals(ctx, cx, cy, radius - 6, s.orientation, yaw);
    }
}
