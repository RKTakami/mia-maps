package com.mia.aperture.client;

import com.mia.aperture.input.InputHandler;
import com.mia.aperture.state.AbyssMapState;
import com.mojang.blaze3d.platform.InputConstants;
import me.cortex.voxy.client.core.util.AbyssUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class MiaApertureModClient implements ClientModInitializer {

    private static KeyMapping mapKeyBind;
    private static KeyMapping toggleCullKeyBind;

    public static com.mia.aperture.map.MapSettings mapSettings = new com.mia.aperture.map.MapSettings();

    public static java.nio.file.Path mapConfigPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mia_aperture_map.json");
    }

    @Override
    public void onInitializeClient() {
        mapSettings = com.mia.aperture.map.MapConfig.load(mapConfigPath());

        // 1. Register Keybinds
        mapKeyBind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mia_aperture_mod.open_map",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                KeyMapping.Category.MISC
        ));

        toggleCullKeyBind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mia_aperture_mod.toggle_cull",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                KeyMapping.Category.MISC
        ));

        // 2. Register Client Tick Event to check keybind presses
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (mapKeyBind.consumeClick()) {
                client.setScreen(new AbyssWorldMapScreen());
            }
            while (toggleCullKeyBind.consumeClick()) {
                AbyssMapState.scrollActive = !AbyssMapState.scrollActive;
                if (AbyssMapState.scrollActive && client.player != null) {
                    // Start culling center at current player Global Y depth
                    var coords = AbyssUtil.toAbyss(client.player.getX(), client.player.getY());
                    AbyssMapState.scrollTargetCenterY = coords.y;
                }
                InputHandler.triggerReevaluation();
                if (client.player != null) {
                    client.player.displayClientMessage(Component.literal("Aperture Cull: " + (AbyssMapState.scrollActive ? "ON" : "OFF")), true);
                }
            }
        });

        // 3. Register HUD Render Callback
        HudRenderCallback.EVENT.register(MiaApertureModClient::drawHud);

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> com.mia.aperture.map.MapCompositor.reset());
    }

    private static void drawHud(GuiGraphics context, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.screen != null || client.options.hideGui) {
            return;
        }

        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();

        // 1. Draw top-down Minimap texture from the tile compositor
        int sector = AbyssUtil.getSection(client.player.getX());
        int bandTop = AbyssMapState.defaultBandTopY(client.player.getY(), sector);
        com.mia.aperture.map.MapCompositor.composeHud(client.player.getX(), client.player.getZ(),
                bandTop, bandTop - AbyssMapState.bandHeight(), AbyssMapState.mapRenderMode);

        int x = screenWidth - 110;
        int y = 10;
        int size = 100;
        context.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xFF111111);
        context.blit(com.mia.aperture.map.MapCompositor.HUD_TEXTURE, x, y, x + size, y + size, 0.0f, 1.0f, 0.0f, 1.0f);
        context.renderOutline(x - 1, y - 1, size + 2, size + 2, 0xFF888888);

        // Draw center crosshair
        int cx = x + size / 2;
        int cy = y + size / 2;
        context.fill(cx - 3, cy, cx + 4, cy + 1, 0x88FF0000);
        context.fill(cx, cy - 3, cx + 1, cy + 4, 0x88FF0000);

        // Draw player position arrow, rotated by player yaw
        context.pose().pushMatrix();
        context.pose().translate(cx + 0.5f, cy + 0.5f);
        float yaw = client.player.getYRot();
        // Minimap is north-up; arrow art points up (north). Rotate to the player's facing:
        // screen facing = (-sin yaw, cos yaw), so the correct angle is yaw + 180.
        context.pose().rotate((float) Math.toRadians(yaw + 180.0f));

        // Compact map-style arrow: solid triangular head with a notched tail
        context.fill(0, -4, 1, -3, 0xFFFFFF00);
        context.fill(-1, -3, 2, -2, 0xFFFFFF00);
        context.fill(-2, -2, 3, -1, 0xFFFFFF00);
        context.fill(-3, -1, 4, 0, 0xFFFFFF00);
        context.fill(-3, 0, -1, 1, 0xFFFFFF00);
        context.fill(2, 0, 4, 1, 0xFFFFFF00);

        context.pose().popMatrix();

        // 2. Draw depth metadata text
        double py = client.player.getY();
        var abyssCoords = AbyssUtil.toAbyss(client.player.getX(), py);
        int physicalDepth = (int) abyssCoords.y;
        int sectionIndex = AbyssUtil.getSection(client.player.getX());
        String layerName = AbyssUtil.getSectionName(sectionIndex);

        int textX = screenWidth - 110;
        int textY = 120;
        context.drawString(client.font, "Depth: " + physicalDepth + "m", textX, textY, 0xFFFFFFFF);
        context.drawString(client.font, "Layer: " + layerName, textX, textY + 10, 0xFF55FF55);

        if (AbyssMapState.scrollActive) {
            context.drawString(client.font, "View: " + (int) AbyssMapState.scrollTargetCenterY + "m", textX, textY + 20, 0xFFFF5555);
        }

        // 3. Draw vertical layer bar sidebar
        drawSidebarLayerBar(context, screenHeight, physicalDepth, layerName);
    }

    private static void drawSidebarLayerBar(GuiGraphics context, int screenHeight, int physicalDepth, String currentLayer) {
        int screenWidth = context.guiWidth();
        int x = screenWidth - 8;
        int startY = 150;
        int endY = screenHeight - 40;
        int barHeight = endY - startY;

        // Draw vertical background line
        context.fill(x, startY, x + 1, endY, 0x44FFFFFF);

        for (int i = 0; i < 15; i++) {
            int tickY = startY + (i * barHeight) / 14;
            String name = AbyssUtil.getSectionName(i);

            context.fill(x - 2, tickY, x, tickY + 1, 0xAAFFFFFF);

            // Highlight current layer tick and text
            if (name.equals(currentLayer)) {
                context.fill(x - 3, tickY - 2, x + 2, tickY + 3, 0xFF55FF55);
                context.drawString(Minecraft.getInstance().font, name, x - 38, tickY - 4, 0xFF55FF55);
            }
        }

        // Draw dynamic scrolling aperture marker in red
        if (AbyssMapState.scrollActive) {
            // Map 0 to -7200m to the slider bar height range
            double depthRatio = -AbyssMapState.scrollTargetCenterY / 7200.0;
            if (depthRatio < 0) depthRatio = 0;
            if (depthRatio > 1) depthRatio = 1;

            int cullMarkerY = startY + (int) (depthRatio * barHeight);
            context.fill(x - 2, cullMarkerY - 2, x + 3, cullMarkerY + 3, 0xFFFF5555);
        }
    }
}
