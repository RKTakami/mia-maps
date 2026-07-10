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
    public static KeyMapping resetKeyBind;

    // Temporary in-game diagnostic for the fullscreen-map FPS regression; remove
    // before the release build.
    public static final boolean DEBUG_PERF_HUD = true;

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

        resetKeyBind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mia_aperture_mod.reset_view",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
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
            while (resetKeyBind.consumeClick()) {
                if (client.player != null) {
                    AbyssMapState.resetDepth(client.player.getX(), client.player.getY());
                    InputHandler.triggerReevaluation();
                    client.player.displayClientMessage(Component.literal("Map depth: reset to you"), true);
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
        var s = mapSettings;
        int size = s.minimapSize;
        int margin = com.mia.aperture.map.MinimapLayout.MARGIN;
        int x = com.mia.aperture.map.MinimapLayout.originX(s.minimapX, screenWidth, size, margin);
        int y = com.mia.aperture.map.MinimapLayout.originY(s.minimapY, screenHeight, size, margin);
        com.mia.aperture.map.MinimapRenderer.draw(context, client.player, x, y, size, s);

        // 2. Draw depth metadata text
        double py = client.player.getY();
        var abyssCoords = AbyssUtil.toAbyss(client.player.getX(), py);
        int physicalDepth = (int) abyssCoords.y;
        int sectionIndex = AbyssUtil.getSection(client.player.getX());
        String layerName = AbyssUtil.getSectionName(sectionIndex);

        int textX = x;
        int textBlockH = 34;
        int textY = (y + size + 6 + textBlockH <= screenHeight) ? (y + size + 6) : (y - textBlockH);
        context.drawString(client.font, "Depth: " + physicalDepth + "m", textX, textY, 0xFFFFFFFF);
        context.drawString(client.font, "Layer: " + layerName, textX, textY + 10, 0xFF55FF55);

        if (AbyssMapState.scrollActive) {
            context.drawString(client.font, "View: " + (int) AbyssMapState.scrollTargetCenterY + "m", textX, textY + 20, 0xFFFF5555);
        }

        // 3. Draw vertical layer bar sidebar
        drawSidebarLayerBar(context, screenHeight, physicalDepth, layerName);

        if (DEBUG_PERF_HUD) {
            String perf = "FPS " + client.getFps()
                    + " | mapQ " + com.mia.aperture.map.MapWorker.queueSize();
            context.drawString(client.font, perf, 4, 4, 0xFFFFFF00);
        }
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
