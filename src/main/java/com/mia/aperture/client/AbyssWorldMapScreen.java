package com.mia.aperture.client;

import com.mia.aperture.input.InputHandler;
import com.mia.aperture.state.AbyssMapState;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

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
        // 1. Draw vanilla background (and widgets, if any)
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawGrid(guiGraphics);

        // 2. Draw the rendered FBO texture stretched to fill the full screen width and height
        // Note: The texture was rendered inside the 3D level rendering pass (WorldRendererMixin)
        int tex = MiaApertureModClient.minimapTextureInstance != null ? MiaApertureModClient.minimapTextureInstance.getGlId() : 0;
        if (tex != 0) {
            guiGraphics.blit(
                    Identifier.fromNamespaceAndPath("mia_aperture_mod", "minimap"),
                    0, 0,
                    0, 0,
                    this.width, this.height,
                    this.width, this.height
            );
        }

        // 3. Draw Map overlay HUD information
        drawMapOverlay(guiGraphics);
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

    private void drawMapOverlay(GuiGraphics guiGraphics) {
        String mode = AbyssMapState.mapPerspective == AbyssMapState.Perspective.TOP_DOWN ? "Perspective: TOP-DOWN (Vertical)" : "Perspective: SIDE-VIEW (Horizontal)";
        guiGraphics.drawString(this.font, mode, 10, 10, 0xFFFFFFFF);
        guiGraphics.drawString(this.font, "Press [P] to toggle perspective", 10, 22, 0xFFAAAAAA);
        guiGraphics.drawString(this.font, "Zoom: " + String.format("%.2f", AbyssMapState.mapZoom) + "x", 10, 34, 0xFFFFFFFF);
        guiGraphics.drawString(this.font, "Aperture depth slice: " + (int) AbyssMapState.scrollTargetCenterY + "m", 10, 46, 0xFFFF5555);
        guiGraphics.drawString(this.font, "Drag to pan | Scroll to zoom | Ctrl+scroll to Y-slice", 10, this.height - 20, 0xFFAAAAAA);
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

    private static long lastScrollLogTime = 0;

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        var window = this.minecraft.getWindow();
        boolean polled = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
                         InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL) ||
                         InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) ||
                         InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
        boolean sliceModifier = AbyssMapState.ctrlHeld || AbyssMapState.altHeld || polled;
        long now = System.currentTimeMillis();
        if (now - lastScrollLogTime > 500) {
            lastScrollLogTime = now;
            System.out.println("[MIA Aperture diag] map scroll: ctrlHeld=" + AbyssMapState.ctrlHeld
                    + " altHeld=" + AbyssMapState.altHeld
                    + " polled=" + polled + " v=" + verticalAmount + " h=" + horizontalAmount);
        }

        if (sliceModifier) {
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
    public boolean keyReleased(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_LEFT_ALT || event.key() == GLFW.GLFW_KEY_RIGHT_ALT) {
            AbyssMapState.altHeld = false;
        }
        if (event.key() == GLFW.GLFW_KEY_LEFT_CONTROL || event.key() == GLFW.GLFW_KEY_RIGHT_CONTROL) {
            AbyssMapState.ctrlHeld = false;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_LEFT_ALT || event.key() == GLFW.GLFW_KEY_RIGHT_ALT) {
            AbyssMapState.altHeld = true;
        }
        if (event.key() == GLFW.GLFW_KEY_LEFT_CONTROL || event.key() == GLFW.GLFW_KEY_RIGHT_CONTROL) {
            AbyssMapState.ctrlHeld = true;
        }
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
