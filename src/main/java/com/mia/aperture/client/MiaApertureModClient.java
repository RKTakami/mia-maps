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
    public static KeyMapping caveKeyBind;
    private static final com.mia.aperture.map.CaveDetector CAVE_DETECTOR = new com.mia.aperture.map.CaveDetector();

    public static com.mia.aperture.map.MapSettings mapSettings = new com.mia.aperture.map.MapSettings();
    public static com.mia.aperture.map.WaypointStore waypoints = new com.mia.aperture.map.WaypointStore();
    public static KeyMapping markWaypointKeyBind;
    public static KeyMapping toggleBeaconsKeyBind;

    public static java.nio.file.Path mapConfigPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mia_aperture_map.json");
    }

    public static java.nio.file.Path waypointConfigPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mia_maps_waypoints.json");
    }

    @Override
    public void onInitializeClient() {
        mapSettings = com.mia.aperture.map.MapConfig.load(mapConfigPath());
        waypoints = com.mia.aperture.map.WaypointConfig.load(waypointConfigPath());

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

        caveKeyBind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mia_aperture_mod.cave_mode",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                KeyMapping.Category.MISC
        ));

        markWaypointKeyBind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mia_aperture_mod.mark_waypoint",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyMapping.Category.MISC
        ));

        toggleBeaconsKeyBind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mia_aperture_mod.toggle_beacons",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                KeyMapping.Category.MISC
        ));

        WaypointChat.register();

        // 2. Register Client Tick Event to check keybind presses
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                com.mia.aperture.map.RouteService.tick(
                        client.player.getX(), client.player.getY(), client.player.getZ());
            }
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
            if (client.player != null && client.level != null) {
                scanEnclosure(client);
            }
            while (caveKeyBind.consumeClick()) {
                var s = mapSettings;
                s.caveMode = switch (s.caveMode) {
                    case AUTO -> com.mia.aperture.map.MapSettings.CaveMode.ON;
                    case ON -> com.mia.aperture.map.MapSettings.CaveMode.OFF;
                    case OFF -> com.mia.aperture.map.MapSettings.CaveMode.AUTO;
                };
                com.mia.aperture.map.MapConfig.save(mapConfigPath(), s);
                if (client.player != null) {
                    client.player.displayClientMessage(Component.literal("Cave Mode: " + s.caveMode), true);
                }
            }
            while (markWaypointKeyBind.consumeClick()) {
                if (client.player != null) {
                    int x = (int) Math.floor(client.player.getX());
                    int y = (int) Math.floor(client.player.getY());
                    int z = (int) Math.floor(client.player.getZ());
                    String skey = com.mia.aperture.map.WaypointStore.currentServerKey(client);
                    client.setScreen(new WaypointEditScreen(null, Component.literal("New Waypoint"),
                            "Waypoint", x, y, z, com.mia.aperture.map.WaypointColor.RED, w -> {
                        waypoints.add(skey, w);
                        com.mia.aperture.map.WaypointConfig.save(waypointConfigPath(), waypoints);
                    }));
                }
            }
            while (toggleBeaconsKeyBind.consumeClick()) {
                mapSettings.showBeacons = !mapSettings.showBeacons;
                com.mia.aperture.map.MapConfig.save(mapConfigPath(), mapSettings);
                if (client.player != null) {
                    client.player.displayClientMessage(Component.literal(
                            "Waypoint beacons: " + (mapSettings.showBeacons ? "ON" : "OFF")), true);
                }
            }
        });

        // 3. Register HUD Render Callback
        HudRenderCallback.EVENT.register(MiaApertureModClient::drawHud);

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    com.mia.aperture.map.MapCompositor.reset();
                    com.mia.aperture.map.OrbitScene.reset();
                });
    }

    private static void scanEnclosure(Minecraft client) {
        var p = client.player;
        int px = (int) Math.floor(p.getX());
        int pz = (int) Math.floor(p.getZ());
        int headY = (int) Math.floor(p.getEyeY());
        boolean found = false;
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= 48; dy++) {
            pos.set(px, headY + dy, pz);
            if (client.level.getBlockState(pos).blocksMotion()) {
                found = true;
                break;
            }
        }
        AbyssMapState.caveEnclosed = CAVE_DETECTOR.debounce(found);
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
        String layerName = layerName(physicalDepth);
        String depthText = mapSettings.depthInMeters
                ? "Depth: " + depthToMeters(physicalDepth) + "m"
                : "Depth: " + (-physicalDepth) + " blocks";

        int textX = x;
        int textBlockH = 44;
        int textY = (y + size + 6 + textBlockH <= screenHeight) ? (y + size + 6) : (y - textBlockH);
        context.drawString(client.font, depthText, textX, textY, 0xFFFFFFFF);
        context.drawString(client.font, "Layer: " + layerName, textX, textY + 10, 0xFF55FF55);
        context.drawString(client.font,
                "X " + (int) Math.floor(client.player.getX())
                        + "  Y " + (int) Math.floor(client.player.getY())
                        + "  Z " + (int) Math.floor(client.player.getZ()),
                textX, textY + 20, 0xFFFFFFFF);

        if (AbyssMapState.scrollActive) {
            context.drawString(client.font, "View: " + (int) AbyssMapState.scrollTargetCenterY + "m", textX, textY + 30, 0xFFFF5555);
        }

        // 3. Draw vertical layer bar sidebar
        drawSidebarLayerBar(context, screenHeight, physicalDepth, layerName);

        // 4. In-world waypoint beacons
        BeaconRenderer.render(context);

        // 5. In-world route + dig path overlay (block markers to follow)
        RoutePathRenderer.render(context);

        // TEMP: mob-tracking diagnostic (remove once classification is tuned).
        context.drawString(client.font, "mobs: " + MobTracker.debug(client), 4, 4, 0xFFFF66FF);
    }

    // Abyss layers by DEPTH below the rim (blocks). physicalDepth = abyssCoords.y is negative
    // going down, so depth-below-rim = -physicalDepth. Add rows as the owner confirms each
    // layer's block range; ranges are [minDepth, maxDepth).
    // Each layer has an in-game BLOCK range (what the HUD depth reads) and the canonical METER
    // range (Made in Abyss lore). blockMin/Max for the top two are owner-measured; the deeper
    // ones are ESTIMATED from the deep-region compression (~0.34 blocks/m, abyss bottom ~7000
    // blocks) — refine by noting the HUD block-depth at each real transition.
    private record AbyssLayer(String name, int blockMin, int blockMax, int meterMin, int meterMax) {}

    private static final AbyssLayer[] LAYERS = {
        new AbyssLayer("Edge of the Abyss",             0,    1510,  0,     1350),
        new AbyssLayer("Forest of Temptation",          1510, 2580,  1350,  2600),
        new AbyssLayer("The Great Fault",               2580, 4090,  2600,  7000),
        new AbyssLayer("The Goblets of Giants",         4090, 5800,  7000,  12000),
        new AbyssLayer("The Sea of Corpses",            5800, 6140,  12000, 13000),
        new AbyssLayer("The Capital of the Unreturned", 6140, 7000,  13000, 15500),
        new AbyssLayer("The Final Whirlpool",           7000, 100000, 15500, 999999),
    };
    private static final double ABYSS_MAX_DEPTH = 7200.0; // sidebar depth scale (blocks)

    private static String layerName(int physicalDepth) {
        int depth = -physicalDepth; // blocks below the rim
        if (depth < 0) return "Orth"; // the surface town, above the rim (elevation > 0)
        for (AbyssLayer l : LAYERS) {
            if (depth >= l.blockMin() && depth < l.blockMax()) return l.name();
        }
        return "Depth " + depth + " (TBD)";
    }

    // Convert in-game block depth to canonical meters via per-layer linear interpolation.
    private static int depthToMeters(int physicalDepth) {
        int depth = -physicalDepth;
        if (depth < 0) return depth; // above the rim (~1:1)
        for (AbyssLayer l : LAYERS) {
            if (depth >= l.blockMin() && depth < l.blockMax()) {
                double t = (double) (depth - l.blockMin()) / Math.max(1, l.blockMax() - l.blockMin());
                return (int) (l.meterMin() + t * (l.meterMax() - l.meterMin()));
            }
        }
        return depth;
    }

    private static void drawSidebarLayerBar(GuiGraphics context, int screenHeight, int physicalDepth, String currentLayer) {
        int screenWidth = context.guiWidth();
        int x = screenWidth - 8;
        int startY = 150;
        int endY = screenHeight - 40;
        int barHeight = endY - startY;

        // Draw vertical background line
        context.fill(x, startY, x + 1, endY, 0x44FFFFFF);

        // One tick per layer, positioned by its depth boundary on the 0..ABYSS_MAX_DEPTH scale.
        for (AbyssLayer l : LAYERS) {
            double ratio = Math.min(1.0, Math.max(0.0, l.blockMin() / ABYSS_MAX_DEPTH));
            int tickY = startY + (int) (ratio * barHeight);
            context.fill(x - 2, tickY, x, tickY + 1, 0xAAFFFFFF);

            if (l.name().equals(currentLayer)) {
                // Highlight the current layer's tick; the name is already in the HUD "Layer:" line,
                // so we don't re-draw it here (it collided with that text when the minimap is right-side).
                context.fill(x - 4, tickY - 2, x + 2, tickY + 3, 0xFF55FF55);
            }
        }

        // Draw dynamic scrolling aperture marker in red
        if (AbyssMapState.scrollActive) {
            // Map 0..ABYSS_MAX_DEPTH to the slider bar height range
            double depthRatio = -AbyssMapState.scrollTargetCenterY / ABYSS_MAX_DEPTH;
            if (depthRatio < 0) depthRatio = 0;
            if (depthRatio > 1) depthRatio = 1;

            int cullMarkerY = startY + (int) (depthRatio * barHeight);
            context.fill(x - 2, cullMarkerY - 2, x + 3, cullMarkerY + 3, 0xFFFF5555);
        }
    }
}
