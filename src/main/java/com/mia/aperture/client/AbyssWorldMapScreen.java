package com.mia.aperture.client;

import com.mia.aperture.input.InputHandler;
import com.mia.aperture.state.AbyssMapState;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class AbyssWorldMapScreen extends Screen {

    private int lastBandTop;

    public AbyssWorldMapScreen() {
        super(Component.literal("Abyss World Map"));
    }

    @Override
    protected void init() {
        super.init();
        // Reset map offsets on open to prevent jumping
        AbyssMapState.mapX = 0.0;
        AbyssMapState.mapZ = 0.0;
        AbyssMapState.mapBandCustom = false;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawGrid(guiGraphics);

        var player = this.minecraft.player;
        if (player != null) {
            int sector = me.cortex.voxy.client.core.util.AbyssUtil.getSection(player.getX());
            int bandTop;
            if (AbyssMapState.mapBandCustom) {
                // scrollTargetCenterY is in ABYSS coords: worldY = abyssY + sector*480
                int worldY = (int) (AbyssMapState.scrollTargetCenterY + sector * 480);
                bandTop = com.mia.aperture.map.MapGeometry.shiftY(worldY, sector)
                        + (int) (AbyssMapState.apertureThickness / 2);
            } else {
                bandTop = AbyssMapState.defaultBandTopY(player.getY(), sector);
            }
            this.lastBandTop = bandTop;
            int bandBottom = bandTop - AbyssMapState.bandHeight();
            int blocksAcross = (int) (256.0f / AbyssMapState.mapZoom);
            double centerX = player.getX() + AbyssMapState.mapX;
            double centerZ = player.getZ() + AbyssMapState.mapZ;
            com.mia.aperture.map.MapCompositor.composeMap(centerX, centerZ, blocksAcross,
                    bandTop, bandBottom, AbyssMapState.mapRenderMode);
        }

        guiGraphics.blit(
                com.mia.aperture.map.MapCompositor.MAP_TEXTURE,
                0, 0,
                this.width, this.height,
                0.0f, 1.0f,
                0.0f, 1.0f
        );

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
        guiGraphics.drawString(this.font, "Mode: " + AbyssMapState.mapRenderMode, 10, 10, 0xFFFFFFFF);
        guiGraphics.drawString(this.font, "Zoom: " + String.format("%.3f", AbyssMapState.mapZoom) + "x", 10, 22, 0xFFFFFFFF);
        // Shifted Y = abyss depth + 3840 (sector-invariant identity), so subtracting 3840
        // displays the band in abyss-depth metres matching the HUD depth readout
        int topAbyss = this.lastBandTop - 3840;
        guiGraphics.drawString(this.font, "Slice: " + topAbyss + "m … " + (topAbyss - AbyssMapState.bandHeight()) + "m"
                + (AbyssMapState.mapBandCustom ? " (custom)" : ""), 10, 34, 0xFFFF5555);
        guiGraphics.drawString(this.font, "Drag to pan | Scroll to zoom | Ctrl+scroll to slice | V: relief/vanilla", 10, this.height - 20, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double scale = (256.0 / (double) this.height) / AbyssMapState.mapZoom;

        AbyssMapState.mapX -= dragX * scale;
        AbyssMapState.mapZ -= dragY * scale;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        var window = this.minecraft.getWindow();
        boolean polled = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
                         InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL) ||
                         InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) ||
                         InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
        boolean sliceModifier = AbyssMapState.ctrlHeld || AbyssMapState.altHeld || polled;

        if (sliceModifier) {
            // Scroll aperture Y level
            AbyssMapState.scrollTargetCenterY += verticalAmount * 16.0;
            AbyssMapState.mapBandCustom = true;
            if (AbyssMapState.scrollActive) {
                InputHandler.triggerReevaluation();
            }
        } else {
            // Zoom map view
            if (verticalAmount > 0) {
                AbyssMapState.mapZoom *= 1.2f;
            } else {
                AbyssMapState.mapZoom *= 0.8f;
            }
            if (AbyssMapState.mapZoom < 0.0125f) AbyssMapState.mapZoom = 0.0125f;
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
        if (event.key() == GLFW.GLFW_KEY_V) {
            AbyssMapState.mapRenderMode = AbyssMapState.mapRenderMode == com.mia.aperture.map.MapMode.RELIEF
                    ? com.mia.aperture.map.MapMode.VANILLA
                    : com.mia.aperture.map.MapMode.RELIEF;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
