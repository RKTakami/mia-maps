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

    // Screen hit-boxes for visible waypoints drawn this frame: {screenX, screenY, wx, wy, wz}.
    // Left-click one to navigate. Rebuilt every render.
    private final java.util.List<double[]> waypointHits = new java.util.ArrayList<>();
    private static final float MIN_ZOOM = 0.03f;
    private final java.util.List<net.minecraft.client.gui.components.Button> mapButtons = new java.util.ArrayList<>();

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

        this.mapButtons.clear();
        this.mapButtons.add(this.addRenderableWidget(
            net.minecraft.client.gui.components.Button.builder(
                Component.literal("Settings"),
                b -> this.minecraft.setScreen(new MapSettingsScreen(this)))
            .bounds(this.width - 90, this.height - 30, 80, 20)
            .build()));

        this.mapButtons.add(this.addRenderableWidget(
            net.minecraft.client.gui.components.Button.builder(
                Component.literal("Reset"),
                b -> {
                    if (this.minecraft.player != null) {
                        AbyssMapState.resetDepth(this.minecraft.player.getX(), this.minecraft.player.getY());
                        InputHandler.triggerReevaluation();
                    }
                })
            .bounds(this.width - 180, this.height - 30, 80, 20)
            .build()));

        this.mapButtons.add(this.addRenderableWidget(
            net.minecraft.client.gui.components.Button.builder(
                Component.literal("Waypoints"),
                b -> this.minecraft.setScreen(new WaypointListScreen(this)))
            .bounds(this.width - 270, this.height - 30, 80, 20)
            .build()));

        this.mapButtons.add(this.addRenderableWidget(
            net.minecraft.client.gui.components.Button.builder(
                Component.literal("3D View"),
                b -> this.minecraft.setScreen(new OrbitView(this)))
            .bounds(this.width - 360, this.height - 30, 80, 20)
            .build()));

        this.mapButtons.add(this.addRenderableWidget(
            net.minecraft.client.gui.components.Button.builder(
                Component.literal("Help"),
                b -> this.minecraft.setScreen(new HelpScreen(this)))
            .bounds(this.width - 450, this.height - 30, 80, 20)
            .build()));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawGrid(guiGraphics);

        var player = this.minecraft.player;
        if (player != null) {
            int sector = me.cortex.voxy.client.core.util.AbyssUtil.getSection(player.getX());
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
            int playerShifted = com.mia.aperture.map.MapGeometry.shiftY((int) player.getY(),
                    me.cortex.voxy.client.core.util.AbyssUtil.getSection(player.getX()));
            com.mia.aperture.map.MapCompositor.composeMap(centerX, centerZ, blocksAcrossX, blocksAcrossZ,
                    bandTop, bandBottom, playerShifted, AbyssMapState.mapRenderMode);
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

            java.util.List<double[]> routePts = com.mia.aperture.map.RouteService.aheadPointsWorld();
            for (int i = 0; i < routePts.size(); i++) {
                double[] rp = routePts.get(i);
                int rx = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        rp[0] - centerX, this.lastBlocksAcrossX, this.width);
                int ry = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        rp[2] - centerZ, this.lastBlocksAcrossZ, this.height);
                if (rx < 0 || rx >= this.width || ry < 0 || ry >= this.height) continue;
                if (i == 0) {
                    com.mia.aperture.map.MarkerShapes.sphere(guiGraphics, rx, ry, 3,
                            com.mia.aperture.map.MinimapRenderer.ROUTE_NEXT_COLOR);
                } else {
                    com.mia.aperture.map.MarkerShapes.sphere(guiGraphics, rx, ry, 1,
                            com.mia.aperture.map.MinimapRenderer.ROUTE_COLOR);
                }
            }

            com.mia.aperture.map.Route.DigPlan dig = com.mia.aperture.map.RouteService.digWorld();
            if (dig != null) {
                int dgx = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        dig.entry()[0] - centerX, this.lastBlocksAcrossX, this.width);
                int dgy = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        dig.entry()[2] - centerZ, this.lastBlocksAcrossZ, this.height);
                int cdx = Math.max(inset, Math.min(this.width - inset, dgx));
                int cdy = Math.max(inset, Math.min(this.height - inset, dgy));
                drawDownTriangle(guiGraphics, cdx, cdy, com.mia.aperture.map.MinimapRenderer.DIG_COLOR);
                guiGraphics.drawString(this.font, "Descend here", cdx + 6, cdy - 4,
                        com.mia.aperture.map.MinimapRenderer.DIG_COLOR);
            }

            this.waypointHits.clear();
            for (com.mia.aperture.map.Waypoint w : MiaApertureModClient.mapSettings.showNavMarkers
                    ? MiaApertureModClient.waypoints.list(wpKey)
                    : java.util.List.<com.mia.aperture.map.Waypoint>of()) {
                if (!w.visible) continue;
                int wx = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        w.x - centerX, this.lastBlocksAcrossX, this.width);
                int wy = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        w.z - centerZ, this.lastBlocksAcrossZ, this.height);
                int cwx = Math.max(inset, Math.min(this.width - inset, wx));
                int cwy = Math.max(inset, Math.min(this.height - inset, wy));
                this.waypointHits.add(new double[]{cwx, cwy, w.x, w.y, w.z});
                drawWaypoint(guiGraphics, cwx, cwy, w.color.argb(), w.name,
                        w.x + ", " + w.y + ", " + w.z);
            }

            double mobRadius = Math.max(this.lastBlocksAcrossX, this.lastBlocksAcrossZ) / 2.0 + 8;
            for (com.mia.aperture.client.MobTracker.Blip bl :
                    com.mia.aperture.client.MobTracker.collect(this.minecraft, mobRadius, 0,
                            MiaApertureModClient.mapSettings)) {
                int bxp = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        bl.x() - centerX, this.lastBlocksAcrossX, this.width);
                int byp = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        bl.z() - centerZ, this.lastBlocksAcrossZ, this.height);
                if (bxp < 0 || bxp >= this.width || byp < 0 || byp >= this.height) continue;
                int color = bl.cat().color;
                guiGraphics.fill(bxp - 1, byp - 1, bxp + 2, byp + 2, color);
                if (bl.cat() == com.mia.aperture.client.MobTracker.Cat.PLAYER
                        || MiaApertureModClient.mapSettings.mobLabels) {
                    guiGraphics.drawString(this.font, bl.name(), bxp + 5, byp - 4, 0xFFFFFFFF);
                }
            }
        }

        var font = this.font;
        int midX = this.width / 2;
        int midY = this.height / 2;
        guiGraphics.drawString(font, "N", midX - font.width("N") / 2, 2, 0xFFFF5555);
        guiGraphics.drawString(font, "S", midX - font.width("S") / 2, this.height - 12, 0xFFFFFFFF);
        guiGraphics.drawString(font, "E", this.width - 10, midY - 4, 0xFFFFFFFF);
        guiGraphics.drawString(font, "W", 2, midY - 4, 0xFFFFFFFF);

        // The full-screen map blit above is drawn over the buttons (super.render ran
        // first), so re-draw them on top to keep them reliably visible.
        for (net.minecraft.client.gui.components.Button b : this.mapButtons) {
            b.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    static com.mia.aperture.map.MapMode nextRenderMode(com.mia.aperture.map.MapMode m) {
        com.mia.aperture.map.MapMode[] all = com.mia.aperture.map.MapMode.values();
        return all[(m.ordinal() + 1) % all.length];
    }

    private void drawDownTriangle(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 3, y - 3, x + 4, y - 2, color);
        g.fill(x - 2, y - 2, x + 3, y - 1, color);
        g.fill(x - 1, y - 1, x + 2, y,     color);
        g.fill(x,     y,     x + 1, y + 1, color);
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
        // An empty map and a broken map look identical, so say which it is. Without this the only
        // signal was a black screen, which reads as a failure even when data is simply still coming.
        if (!com.mia.aperture.map.MapCompositor.dataReady()) {
            String msg = "Waiting for world data...";
            int w = this.font.width(msg) + 16;
            int x = (this.width - w) / 2, y = this.height / 2 - 6;
            SteamTheme.panel(guiGraphics, x, y, w, 16);
            guiGraphics.drawString(this.font, msg, x + 8, y + 4, 0xFFFFAA33);
        }

        // Status readouts, each on its own brass-edged plate. Bare text sat directly on the terrain
        // and became unreadable over anything pale — these are instruments, so they get instrument
        // housings, and the dark backing is what actually makes the numbers legible.
        String src = com.mia.aperture.map.MapCompositor.lastSourceWasStore ? "store" : "Voxy";
        String why = com.mia.aperture.map.MapCompositor.storeBlockedReason;
        int topAbyss = this.lastBandTop - 3840;
        var marker = this.minecraft.player;

        int y = 10;
        y += SteamTheme.readout(guiGraphics, this.font,
                "Mode " + AbyssMapState.mapRenderMode + "   Source " + src
                        + (why != null ? " (" + why + ")" : ""),
                10, y, SteamTheme.INK);
        y += SteamTheme.readout(guiGraphics, this.font,
                "Zoom " + String.format("%.3f", AbyssMapState.mapZoom) + "x", 10, y, SteamTheme.INK);
        y += SteamTheme.readout(guiGraphics, this.font,
                "Slice " + topAbyss + "m \u2026 " + (topAbyss - AbyssMapState.bandHeight()) + "m"
                        + (AbyssMapState.mapDepthActive ? " (custom)" : ""),
                10, y, 0xFFFF8866);
        if (marker != null) {
            y += SteamTheme.readout(guiGraphics, this.font,
                    "X " + (int) Math.floor(marker.getX())
                            + "   Y " + (int) Math.floor(marker.getY())
                            + "   Z " + (int) Math.floor(marker.getZ()),
                    10, y, SteamTheme.INK);
        }

        // Corner brackets rather than a full frame: at this size a continuous bezel would eat the
        // edges of the map itself, and four brackets carry the same "instrument" reading.
        SteamTheme.corners(guiGraphics, 4, 4, this.width - 8, this.height - 8, 10);
        // The windlass earns its place back: it works the right margin, which is the one strip of this
        // screen with nothing in it. The flourishes and the lower-left gears do NOT come back — those
        // sat on the readout column and the help bar, which is what made the screen feel crowded.
        int shaft = Math.max(60, this.height - 210);
        SteamOrnament.windlassBasket(guiGraphics, this.width - 34, 74, shaft, 14000);

        String help = "Drag: pan | Scroll: zoom | Ctrl+scroll: slice | Shift+right-click: waypoint"
                + " | click waypoint: navigate | V: mode";
        SteamTheme.readout(guiGraphics, this.font, help, 10, this.height - 20, SteamTheme.INK_DIM);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Let buttons (Waypoints/Settings/etc.) consume the click first.
        if (super.mouseClicked(event, doubled)) return true;
        var player = this.minecraft.player;
        if (player == null) return false;

        // Left-click on a visible waypoint marker: navigate to it.
        if (event.button() == 0) {
            for (double[] h : this.waypointHits) {
                if (Math.abs(event.x() - h[0]) <= 8 && Math.abs(event.y() - h[1]) <= 8) {
                    com.mia.aperture.map.RouteService.setDestination(h[2], h[3], h[4]);
                    return true;
                }
            }
        }

        // Shift+right-click: create a waypoint at the clicked world X/Z (Y = player's, editable).
        if (event.button() == 1 && (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) {
            double centerX = player.getX() + AbyssMapState.mapX;
            double centerZ = player.getZ() + AbyssMapState.mapZ;
            int wx = (int) Math.floor(centerX + com.mia.aperture.map.MapGeometry.worldDeltaFromPixel(
                    event.x(), this.lastBlocksAcrossX, this.width));
            int wz = (int) Math.floor(centerZ + com.mia.aperture.map.MapGeometry.worldDeltaFromPixel(
                    event.y(), this.lastBlocksAcrossZ, this.height));
            int wy = (int) Math.floor(player.getY());
            String key = com.mia.aperture.map.WaypointStore.currentServerKey(this.minecraft);
            this.minecraft.setScreen(new WaypointEditScreen(this, Component.literal("New Waypoint"),
                    "Waypoint", wx, wy, wz, com.mia.aperture.map.WaypointColor.RED, w -> {
                        MiaApertureModClient.waypoints.add(key, w);
                        com.mia.aperture.map.WaypointConfig.save(
                                MiaApertureModClient.waypointConfigPath(), MiaApertureModClient.waypoints);
                    }));
            return true;
        }
        return false;
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
            AbyssMapState.mapRenderMode = nextRenderMode(AbyssMapState.mapRenderMode);
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
