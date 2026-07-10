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
    private int lastBlocksAcrossX = 1;
    private int lastBlocksAcrossZ = 1;
    private static final float MIN_ZOOM = 0.03f;

    public AbyssWorldMapScreen() {
        super(Component.literal("Abyss World Map"));
    }

    @Override
    protected void init() {
        super.init();
        // Reset pan offsets on open to prevent jumping; keep the depth cut so a slice
        // set in-world persists when the map is opened.
        AbyssMapState.mapX = 0.0;
        AbyssMapState.mapZ = 0.0;

        this.addRenderableWidget(
            net.minecraft.client.gui.components.Button.builder(
                Component.literal("Settings"),
                b -> this.minecraft.setScreen(new MapSettingsScreen(this)))
            .bounds(this.width - 90, this.height - 30, 80, 20)
            .build());

        this.addRenderableWidget(
            net.minecraft.client.gui.components.Button.builder(
                Component.literal("Reset"),
                b -> {
                    if (this.minecraft.player != null) {
                        AbyssMapState.resetDepth(this.minecraft.player.getX(), this.minecraft.player.getY());
                        InputHandler.triggerReevaluation();
                    }
                })
            .bounds(this.width - 180, this.height - 30, 80, 20)
            .build());

        this.addRenderableWidget(
            net.minecraft.client.gui.components.Button.builder(
                Component.literal("Waypoints"),
                b -> this.minecraft.setScreen(new WaypointListScreen(this)))
            .bounds(this.width - 270, this.height - 30, 80, 20)
            .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawGrid(guiGraphics);

        var player = this.minecraft.player;
        if (player != null) {
            int sector = me.cortex.voxy.client.core.util.AbyssUtil.getSection(player.getX());
            boolean caveActive = com.mia.aperture.map.CaveDetector.caveActive(
                    MiaApertureModClient.mapSettings.caveMode, AbyssMapState.caveEnclosed);
            int bandTop = AbyssMapState.mapBandTopShifted((int) player.getY(), sector,
                    AbyssMapState.mapDepthActive, AbyssMapState.scrollTargetCenterY);
            this.lastBandTop = bandTop;
            int bandBottom = bandTop - AbyssMapState.bandHeight();
            int base = (int) (256.0f / AbyssMapState.mapZoom);
            double aspect = (double) this.width / this.height;
            int blocksAcrossX = Math.max(1, (int) Math.round(base * aspect));
            int blocksAcrossZ = base;
            this.lastBlocksAcrossX = blocksAcrossX;
            this.lastBlocksAcrossZ = blocksAcrossZ;
            double centerX = player.getX() + AbyssMapState.mapX;
            double centerZ = player.getZ() + AbyssMapState.mapZ;
            com.mia.aperture.map.MapCompositor.composeMap(centerX, centerZ, blocksAcrossX, blocksAcrossZ,
                    bandTop, bandBottom, caveActive ? com.mia.aperture.map.MapMode.CAVE : AbyssMapState.mapRenderMode);
        }

        guiGraphics.blit(
                com.mia.aperture.map.MapCompositor.MAP_TEXTURE,
                0, 0,
                this.width, this.height,
                0.0f, 1.0f,
                0.0f, 1.0f
        );

        drawMapOverlay(guiGraphics);

        if (player != null) {
            int mx = com.mia.aperture.map.MapGeometry.playerMarkerX(
                    AbyssMapState.mapX, this.lastBlocksAcrossX, this.width);
            int my = com.mia.aperture.map.MapGeometry.playerMarkerY(
                    AbyssMapState.mapZ, this.lastBlocksAcrossZ, this.height);
            int inset = 6;
            int cmx = Math.max(inset, Math.min(this.width - inset, mx));
            int cmy = Math.max(inset, Math.min(this.height - inset, my));
            drawPlayerMarker(guiGraphics, cmx, cmy, player.getYRot());

            String wpKey = com.mia.aperture.map.WaypointStore.currentServerKey(this.minecraft);
            double centerX = player.getX() + AbyssMapState.mapX;
            double centerZ = player.getZ() + AbyssMapState.mapZ;
            for (com.mia.aperture.map.Waypoint w : MiaApertureModClient.waypoints.list(wpKey)) {
                int wx = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        w.x - centerX, this.lastBlocksAcrossX, this.width);
                int wy = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        w.z - centerZ, this.lastBlocksAcrossZ, this.height);
                int cwx = Math.max(inset, Math.min(this.width - inset, wx));
                int cwy = Math.max(inset, Math.min(this.height - inset, wy));
                drawWaypoint(guiGraphics, cwx, cwy, w.color.argb(), w.name,
                        w.x + ", " + w.y + ", " + w.z);
            }
        }

        var font = this.font;
        int midX = this.width / 2;
        int midY = this.height / 2;
        guiGraphics.drawString(font, "N", midX - font.width("N") / 2, 2, 0xFFFF5555);
        guiGraphics.drawString(font, "S", midX - font.width("S") / 2, this.height - 12, 0xFFFFFFFF);
        guiGraphics.drawString(font, "E", this.width - 10, midY - 4, 0xFFFFFFFF);
        guiGraphics.drawString(font, "W", 2, midY - 4, 0xFFFFFFFF);
    }

    private void drawWaypoint(GuiGraphics g, int cx, int cy, int color, String name, String coords) {
        // small diamond
        g.fill(cx, cy - 3, cx + 1, cy + 4, color);
        g.fill(cx - 1, cy - 2, cx + 2, cy + 3, color);
        g.fill(cx - 2, cy - 1, cx + 3, cy + 2, color);
        g.fill(cx - 3, cy, cx + 4, cy + 1, color);
        // name above, coordinates below, beside the diamond
        g.drawString(this.font, name, cx + 6, cy - 9, 0xFFFFFFFF);
        g.drawString(this.font, coords, cx + 6, cy + 2, 0xFFB0B0B0);
    }

    private void drawPlayerMarker(GuiGraphics g, int cx, int cy, float yaw) {
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);
        g.pose().pushMatrix();
        g.pose().translate(cx + 0.5f, cy + 0.5f);
        g.pose().rotate((float) Math.toRadians(yaw + 180.0f));
        int pc = com.mia.aperture.map.MinimapRenderer.PLAYER_COLOR;
        g.fill(0, -9, 1, -6, pc);   // slender tip
        g.fill(-1, -6, 2, -4, pc);
        g.fill(-2, -4, 3, -2, pc);
        g.fill(-3, -2, 4, 0, pc);
        g.fill(-4, 0, -1, 2, pc);   // left wing (notched base)
        g.fill(2, 0, 5, 2, pc);     // right wing
        g.pose().popMatrix();
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
                + (AbyssMapState.mapDepthActive ? " (custom)" : ""), 10, 34, 0xFFFF5555);
        var marker = this.minecraft.player;
        if (marker != null) {
            guiGraphics.drawString(this.font,
                    "X " + (int) Math.floor(marker.getX())
                            + "  Y " + (int) Math.floor(marker.getY())
                            + "  Z " + (int) Math.floor(marker.getZ()),
                    10, 46, 0xFFFFFFFF);
        }
        guiGraphics.drawString(this.font, "Drag to pan | Scroll to zoom | Ctrl+scroll to slice | Reset returns to you | V: relief/vanilla", 10, this.height - 20, 0xFFAAAAAA);
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
            // Move the depth cut; the map shows the surface just below it
            AbyssMapState.scrollTargetCenterY += verticalAmount * AbyssMapState.SCROLL_STEP;
            AbyssMapState.mapDepthActive = true;
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
            if (AbyssMapState.mapZoom < MIN_ZOOM) AbyssMapState.mapZoom = MIN_ZOOM;
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
        if (MiaApertureModClient.resetKeyBind != null
                && MiaApertureModClient.resetKeyBind.matches(event)) {
            if (this.minecraft.player != null) {
                AbyssMapState.resetDepth(this.minecraft.player.getX(), this.minecraft.player.getY());
                InputHandler.triggerReevaluation();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        // Cancel the tile-worker backlog and free the 2048² map texture so neither
        // keeps costing frames once the fullscreen map is closed (the tile cache and
        // HUD texture are kept for a fast reopen and a live minimap).
        com.mia.aperture.map.MapWorker.cancelPending();
        com.mia.aperture.map.MapCompositor.freeMapTexture();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
