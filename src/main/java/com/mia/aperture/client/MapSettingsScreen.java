package com.mia.aperture.client;

import com.mia.aperture.map.MapConfig;
import com.mia.aperture.map.MapSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MapSettingsScreen extends Screen {
    private final Screen parent;

    public MapSettingsScreen(Screen parent) {
        super(Component.literal("Map Settings"));
        this.parent = parent;
    }

    private static MapSettings settings() {
        return MiaApertureModClient.mapSettings;
    }

    private static void persist() {
        MapConfig.save(MiaApertureModClient.mapConfigPath(), settings());
    }

    private static void setCorner(MapSettings.MinimapCorner corner) {
        double[] f = com.mia.aperture.map.MinimapLayout.cornerFraction(corner);
        settings().setMinimapPos(f[0], f[1]);
        persist();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4;

        this.addRenderableWidget(Button.builder(orientationLabel(), b -> {
            MapSettings s = settings();
            s.orientation = s.orientation == MapSettings.Orientation.NORTH_UP
                    ? MapSettings.Orientation.HEADING_UP : MapSettings.Orientation.NORTH_UP;
            b.setMessage(orientationLabel());
            persist();
        }).bounds(cx - 100, y, 200, 20).build());

        this.addRenderableWidget(Button.builder(shapeLabel(), b -> {
            MapSettings s = settings();
            s.shape = s.shape == MapSettings.FrameShape.SQUARE
                    ? MapSettings.FrameShape.ROUND : MapSettings.FrameShape.SQUARE;
            b.setMessage(shapeLabel());
            persist();
        }).bounds(cx - 100, y + 24, 200, 20).build());

        this.addRenderableWidget(new AbstractSliderButton(cx - 100, y + 48, 200, 20,
                sizeLabel(), sizeToValue(settings().minimapSize)) {
            @Override protected void updateMessage() { setMessage(sizeLabel()); }
            @Override protected void applyValue() {
                int px = MapSettings.MIN_SIZE
                        + (int) Math.round(this.value * (MapSettings.MAX_SIZE - MapSettings.MIN_SIZE));
                settings().setMinimapSize(px);
            }
        });

        int cy2 = y + 108;
        this.addRenderableWidget(Button.builder(Component.literal("TL"), b -> setCorner(MapSettings.MinimapCorner.TOP_LEFT))
                .bounds(cx - 100, cy2, 46, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("TR"), b -> setCorner(MapSettings.MinimapCorner.TOP_RIGHT))
                .bounds(cx - 50, cy2, 46, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("BL"), b -> setCorner(MapSettings.MinimapCorner.BOTTOM_LEFT))
                .bounds(cx + 4, cy2, 46, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("BR"), b -> setCorner(MapSettings.MinimapCorner.BOTTOM_RIGHT))
                .bounds(cx + 54, cy2, 46, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Reposition (drag)"),
                b -> this.minecraft.setScreen(new MinimapRepositionScreen(this)))
                .bounds(cx - 100, cy2 + 24, 200, 20).build());

        this.addRenderableWidget(Button.builder(caveLabel(), b -> {
            MapSettings s = settings();
            s.caveMode = switch (s.caveMode) {
                case AUTO -> MapSettings.CaveMode.ON;
                case ON -> MapSettings.CaveMode.OFF;
                case OFF -> MapSettings.CaveMode.AUTO;
            };
            b.setMessage(caveLabel());
            persist();
        }).bounds(cx - 100, cy2 + 48, 200, 20).build());

        this.addRenderableWidget(Button.builder(beaconLabel(), b -> {
            settings().showBeacons = !settings().showBeacons;
            b.setMessage(beaconLabel());
            persist();
        }).bounds(cx - 100, cy2 + 72, 200, 20).build());

        this.addRenderableWidget(Button.builder(orbitQualityLabel(), b -> {
            settings().orbitQuality = settings().orbitQuality.next();
            b.setMessage(orbitQualityLabel());
            persist();
        }).bounds(cx - 100, cy2 + 96, 200, 20).build());

        this.addRenderableWidget(Button.builder(safeDropLabel(), b -> {
            MapSettings s = settings();
            int next = s.safeDropBlocks + 1;
            if (next > MapSettings.MAX_SAFE_DROP) next = MapSettings.MIN_SAFE_DROP;
            s.setSafeDropBlocks(next);
            b.setMessage(safeDropLabel());
            persist();
        }).bounds(cx - 100, cy2 + 120, 200, 20).build());

        this.addRenderableWidget(Button.builder(mobLabel("Hostiles", settings().trackHostiles), b -> {
            settings().trackHostiles = !settings().trackHostiles;
            b.setMessage(mobLabel("Hostiles", settings().trackHostiles));
            persist();
        }).bounds(cx - 100, cy2 + 144, 98, 20).build());
        this.addRenderableWidget(Button.builder(mobLabel("Players", settings().trackPlayers), b -> {
            settings().trackPlayers = !settings().trackPlayers;
            b.setMessage(mobLabel("Players", settings().trackPlayers));
            persist();
        }).bounds(cx + 2, cy2 + 144, 98, 20).build());
        this.addRenderableWidget(Button.builder(mobLabel("Passive", settings().trackPassive), b -> {
            settings().trackPassive = !settings().trackPassive;
            b.setMessage(mobLabel("Passive", settings().trackPassive));
            persist();
        }).bounds(cx - 100, cy2 + 168, 98, 20).build());
        this.addRenderableWidget(Button.builder(mobLabel("Labels", settings().mobLabels), b -> {
            settings().mobLabels = !settings().mobLabels;
            b.setMessage(mobLabel("Labels", settings().mobLabels));
            persist();
        }).bounds(cx + 2, cy2 + 168, 98, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, cy2 + 192, 200, 20).build());
    }

    private static double sizeToValue(int px) {
        return (px - MapSettings.MIN_SIZE) / (double) (MapSettings.MAX_SIZE - MapSettings.MIN_SIZE);
    }

    private static Component orientationLabel() {
        return Component.literal("Orientation: " + (settings().orientation == MapSettings.Orientation.NORTH_UP
                ? "North-locked" : "Rotate with facing"));
    }
    private static Component shapeLabel() {
        return Component.literal("Frame: " + (settings().shape == MapSettings.FrameShape.SQUARE
                ? "Square" : "Round"));
    }
    private static Component sizeLabel() {
        return Component.literal("Minimap size: " + settings().minimapSize + "px");
    }
    private static Component caveLabel() {
        return Component.literal("Cave Mode: " + settings().caveMode);
    }
    private static Component beaconLabel() {
        return Component.literal("Waypoint beacons: " + (settings().showBeacons ? "On" : "Off"));
    }
    private static Component orbitQualityLabel() {
        MapSettings.OrbitQuality q = settings().orbitQuality;
        return Component.literal("3D Quality: " + q.label + " (" + q.textureSize + "px)");
    }
    private static Component safeDropLabel() {
        return Component.literal("Safe fall distance: " + settings().safeDropBlocks + " blocks");
    }
    private static Component mobLabel(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "On" : "Off"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 20, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        persist();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
