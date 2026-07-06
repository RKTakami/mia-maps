package com.mia.aperture.client;

import com.mia.aperture.duck.VoxyRenderSystemDuck;
import com.mia.aperture.input.InputHandler;
import com.mia.aperture.mixin.ViewportSelectorInvoker;
import com.mia.aperture.state.AbyssMapState;
import com.mojang.blaze3d.platform.InputConstants;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.AbyssUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import static org.lwjgl.opengl.GL11.*;

public class AbyssWorldMapScreen extends Screen {

    public AbyssWorldMapScreen() {
        super(Component.literal("Abyss World Map"));
    }

    @Override
    protected void init() {
        super.init();
        // Reset map offsets on open to prevent jumping
        AbyssMapState.mapX = 0.0;
        AbyssMapState.mapY = 0.0;
        AbyssMapState.mapZ = 0.0;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 1. Draw background
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        drawGrid(guiGraphics);

        // 2. Fetch VoxyRenderSystem
        VoxyRenderSystem renderSystem = IGetVoxyRenderSystem.getNullable();
        if (renderSystem != null) {
            renderVoxyMap(renderSystem);
        }

        // 3. Draw Map overlay HUD information
        drawMapOverlay(guiGraphics);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void drawGrid(GuiGraphics guiGraphics) {
        int width = this.width;
        int height = this.height;
        int gridSpacing = 40;

        for (int x = 0; x < width; x += gridSpacing) {
            guiGraphics.fill(x, 0, x + 1, height, 0x11FFFFFF);
        }
        for (int y = 0; y < height; y += gridSpacing) {
            guiGraphics.fill(0, y, width, y + 1, 0x11FFFFFF);
        }
    }

    private void renderVoxyMap(VoxyRenderSystem renderSystem) {
        int fbWidth = this.minecraft.getWindow().getWidth();
        int fbHeight = this.minecraft.getWindow().getHeight();

        // Save viewport
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);

        glViewport(0, 0, fbWidth, fbHeight);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);

        // Get player coordinates
        double px = this.minecraft.player.getX();
        double py = this.minecraft.player.getY();
        double pz = this.minecraft.player.getZ();

        AbyssUtil.Coords abyssCoords = AbyssUtil.toAbyss(px, py);
        double ax = abyssCoords.x;
        double ay = abyssCoords.y;

        float aspect = (float) fbWidth / (float) fbHeight;
        float halfSize = 128.0f / AbyssMapState.mapZoom;

        Matrix4f projection = new Matrix4f().setOrtho(-halfSize * aspect, halfSize * aspect, -halfSize, halfSize, 0.05f, 2000.0f);
        Matrix4f modelView = new Matrix4f();

        double camX = ax;
        double camY = ay;
        double camZ = pz;

        if (AbyssMapState.mapPerspective == AbyssMapState.Perspective.TOP_DOWN) {
            camX = ax + AbyssMapState.mapX;
            camY = ay + 1000.0; // Place camera 1000 blocks high
            camZ = pz + AbyssMapState.mapZ;

            modelView.rotateX((float) Math.toRadians(90.0)) // Pitch 90 to look straight down
                     .rotateY((float) Math.toRadians(180.0)) // Rotate to align North
                     .translate((float) -camX, (float) -camY, (float) -camZ);
        } else {
            // SIDE_VIEW (Z horizontal, Y vertical cross-section)
            camX = ax + 1000.0; // Place camera 1000 blocks East to look Westward
            camY = ay + AbyssMapState.mapY;
            camZ = pz + AbyssMapState.mapZ;

            modelView.rotateY((float) Math.toRadians(90.0)) // Look from East to West
                     .translate((float) -camX, (float) -camY, (float) -camZ);
        }

        ViewportSelectorInvoker selector = (ViewportSelectorInvoker) ((VoxyRenderSystemDuck) renderSystem).mia$getViewportSelector();
        Viewport<?> viewport = selector.mia$getOrCreate(MinimapFbo.MIA_MAP_VIEWPORT_KEY);

        // Copy active main viewport parameters to avoid blank shadow/sky rendering bugs
        Viewport<?> mainViewport = ((VoxyRenderSystemDuck) renderSystem).mia$getViewportSelector().getViewport();
        if (mainViewport != null && mainViewport.fogParameters != null) {
            viewport.setFogParameters(mainViewport.fogParameters);
        }

        viewport.setVanillaProjection(projection)
                .setProjection(projection)
                .setModelView(modelView)
                .setCamera(camX, camY, camZ)
                .setScreenSize(fbWidth, fbHeight)
                .update();

        renderSystem.renderOpaque(viewport);

        glDisable(GL_DEPTH_TEST);
        glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
    }

    private void drawMapOverlay(GuiGraphics guiGraphics) {
        String mode = AbyssMapState.mapPerspective == AbyssMapState.Perspective.TOP_DOWN ? "Perspective: TOP-DOWN (Vertical)" : "Perspective: SIDE-VIEW (Horizontal)";
        guiGraphics.drawString(this.font, mode, 10, 10, 0xFFFFFFFF);
        guiGraphics.drawString(this.font, "Press [P] to toggle perspective", 10, 22, 0xFFAAAAAA);
        guiGraphics.drawString(this.font, "Zoom: " + String.format("%.2f", AbyssMapState.mapZoom) + "x", 10, 34, 0xFFFFFFFF);
        guiGraphics.drawString(this.font, "Aperture depth slice: " + (int) AbyssMapState.scrollTargetCenterY + "m", 10, 46, 0xFFFF5555);
        guiGraphics.drawString(this.font, "Drag to pan | Scroll to zoom | Alt+scroll to Y-slice", 10, this.height - 20, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double scale = (256.0 / (double) this.height) / AbyssMapState.mapZoom;

        if (AbyssMapState.mapPerspective == AbyssMapState.Perspective.TOP_DOWN) {
            AbyssMapState.mapX -= dragX * scale;
            AbyssMapState.mapZ -= dragY * scale;
        } else {
            AbyssMapState.mapZ -= dragX * scale;
            AbyssMapState.mapY += dragY * scale;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        var window = this.minecraft.getWindow();
        boolean altDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) || 
                          InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);

        if (altDown) {
            // Scroll aperture Y level
            AbyssMapState.scrollTargetCenterY += verticalAmount * 16.0;
            InputHandler.triggerReevaluation();
        } else {
            // Zoom map view
            if (verticalAmount > 0) {
                AbyssMapState.mapZoom *= 1.2f;
            } else {
                AbyssMapState.mapZoom *= 0.8f;
            }
            if (AbyssMapState.mapZoom < 0.1f) AbyssMapState.mapZoom = 0.1f;
            if (AbyssMapState.mapZoom > 20.0f) AbyssMapState.mapZoom = 20.0f;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_P) {
            // Toggle perspective
            AbyssMapState.mapPerspective = (AbyssMapState.mapPerspective == AbyssMapState.Perspective.TOP_DOWN)
                    ? AbyssMapState.Perspective.SIDE_VIEW
                    : AbyssMapState.Perspective.TOP_DOWN;
            AbyssMapState.mapX = 0.0;
            AbyssMapState.mapY = 0.0;
            AbyssMapState.mapZ = 0.0;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
