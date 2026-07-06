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
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class MiaApertureModClient implements ClientModInitializer {

    private static KeyMapping mapKeyBind;
    private static KeyMapping toggleCullKeyBind;
    public static MinimapTexture minimapTextureInstance;
    private static long lastHudLogTime = 0;

    public static class MinimapTexture extends AbstractTexture {
        public MinimapTexture() {
            // Allocate texture objects on GpuDevice
            this.texture = com.mojang.blaze3d.systems.RenderSystem.getDevice().createTexture(
                    "minimap",
                    1,
                    com.mojang.blaze3d.textures.TextureFormat.RGBA8,
                    512,
                    512,
                    1,
                    1
            );
            this.textureView = com.mojang.blaze3d.systems.RenderSystem.getDevice().createTextureView(this.texture);
            this.sampler = com.mojang.blaze3d.systems.RenderSystem.getDevice().createSampler(
                    com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                    com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                    com.mojang.blaze3d.textures.FilterMode.LINEAR,
                    com.mojang.blaze3d.textures.FilterMode.LINEAR,
                    1,
                    java.util.OptionalDouble.empty()
            );
            System.out.println("[MIA Aperture] MinimapTexture initialized. GpuTexture Class: " 
                + (this.texture != null ? this.texture.getClass().getName() : "null") 
                + ", GL ID: " + getGlId());
        }

        public int getGlId() {
            if (this.texture == null) return 0;
            // 1. Direct cast check
            if (this.texture instanceof com.mojang.blaze3d.opengl.GlTexture glTex) {
                return glTex.glId();
            }
            // 2. Reflection fallback for wrapped classes
            try {
                for (Field field : this.texture.getClass().getDeclaredFields()) {
                    if (field.getName().equals("id") || field.getName().equals("glId")) {
                        field.setAccessible(true);
                        return ((Number) field.get(this.texture)).intValue();
                    }
                }
                for (Method method : this.texture.getClass().getDeclaredMethods()) {
                    if (method.getName().equals("glId")) {
                        method.setAccessible(true);
                        return ((Number) method.invoke(this.texture)).intValue();
                    }
                }
            } catch (Exception e) {
                // Silent catch
            }
            return 0;
        }

        @Override
        public void close() {
            super.close();
            // Free GpuTexture resources
            if (this.texture != null) {
                this.texture.close();
            }
        }
    }

    public static void ensureTextureInitialized() {
        if (minimapTextureInstance == null) {
            try {
                minimapTextureInstance = new MinimapTexture();
                Minecraft.getInstance().getTextureManager().register(
                        Identifier.fromNamespaceAndPath("mia_aperture_mod", "minimap"),
                        minimapTextureInstance
                );
            } catch (Exception e) {
                System.err.println("[MIA Aperture] Failed to lazily initialize MinimapTexture:");
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onInitializeClient() {
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
    }

    private static void drawHud(GuiGraphics context, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.screen != null || client.options.hideGui) {
            return;
        }

        // Ensure texture is loaded
        ensureTextureInitialized();

        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();

        // 1. Draw top-down Minimap texture from FBO
        int tex = minimapTextureInstance != null ? minimapTextureInstance.getGlId() : 0;
        
        long now = System.currentTimeMillis();
        if (now - lastHudLogTime > 5000) {
            lastHudLogTime = now;
            System.out.println("[MIA Aperture debug] drawHud: minimapTextureInstance=" + (minimapTextureInstance != null) 
                + ", tex=" + tex + ", GL texture view=" + (minimapTextureInstance != null ? minimapTextureInstance.getTextureView() != null : "null"));
        }

        if (tex != 0) {
            int x = screenWidth - 110;
            int y = 10;
            int size = 100;

            // Draw background frame
            context.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xFF111111);
            context.blit(Identifier.fromNamespaceAndPath("mia_aperture_mod", "minimap"), x, y, 0, 0, size, size, size, size);
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
            context.pose().rotate((float) Math.toRadians(-yaw - 180.0f));

            // Small yellow arrow shape
            context.fill(-1, -6, 1, 4, 0xFFFFFF00);
            context.fill(-3, 2, -1, 4, 0xFFFFFF00);
            context.fill(1, 2, 3, 4, 0xFFFFFF00);
            context.pose().popMatrix();
        }

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
