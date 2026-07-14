package com.mia.aperture.client;

import com.mia.aperture.map.MapConfig;
import com.mia.aperture.map.MapSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class MapSettingsScreen extends Screen {
    private final Screen parent;

    private static final int ROW_H = 24;
    private static final int CONTENT_TOP = 40;

    // Scrollable content widgets (rendered + hit-tested at baseY - scrollOffset, clipped to the
    // content viewport). Title + Done are fixed outside the scroll region.
    private final List<AbstractWidget> scrollWidgets = new ArrayList<>();
    private final List<Integer> baseY = new ArrayList<>();
    private double scrollOffset;
    private int contentBottom;
    private int maxScroll;
    private Button doneButton;

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

    private <T extends AbstractWidget> T addScroll(T w, int row) {
        w.setY(CONTENT_TOP + row * ROW_H);
        this.addWidget(w);
        scrollWidgets.add(w);
        baseY.add(CONTENT_TOP + row * ROW_H);
        return w;
    }

    @Override
    protected void init() {
        scrollWidgets.clear();
        baseY.clear();
        int cx = this.width / 2;
        contentBottom = this.height - 34;

        int r = 0;
        addScroll(Button.builder(orientationLabel(), b -> {
            MapSettings s = settings();
            s.orientation = s.orientation == MapSettings.Orientation.NORTH_UP
                    ? MapSettings.Orientation.HEADING_UP : MapSettings.Orientation.NORTH_UP;
            b.setMessage(orientationLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);

        addScroll(Button.builder(shapeLabel(), b -> {
            MapSettings s = settings();
            s.shape = s.shape == MapSettings.FrameShape.SQUARE
                    ? MapSettings.FrameShape.ROUND : MapSettings.FrameShape.SQUARE;
            b.setMessage(shapeLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);

        addScroll(new AbstractSliderButton(cx - 100, 0, 200, 20,
                sizeLabel(), sizeToValue(settings().minimapSize)) {
            @Override protected void updateMessage() { setMessage(sizeLabel()); }
            @Override protected void applyValue() {
                int px = MapSettings.MIN_SIZE
                        + (int) Math.round(this.value * (MapSettings.MAX_SIZE - MapSettings.MIN_SIZE));
                settings().setMinimapSize(px);
            }
        }, r++);

        int cornerRow = r++;
        addScroll(Button.builder(Component.literal("TL"), b -> setCorner(MapSettings.MinimapCorner.TOP_LEFT))
                .bounds(cx - 100, 0, 46, 20).build(), cornerRow);
        addScroll(Button.builder(Component.literal("TR"), b -> setCorner(MapSettings.MinimapCorner.TOP_RIGHT))
                .bounds(cx - 50, 0, 46, 20).build(), cornerRow);
        addScroll(Button.builder(Component.literal("BL"), b -> setCorner(MapSettings.MinimapCorner.BOTTOM_LEFT))
                .bounds(cx + 4, 0, 46, 20).build(), cornerRow);
        addScroll(Button.builder(Component.literal("BR"), b -> setCorner(MapSettings.MinimapCorner.BOTTOM_RIGHT))
                .bounds(cx + 54, 0, 46, 20).build(), cornerRow);

        addScroll(Button.builder(Component.literal("Reposition (drag)"),
                b -> this.minecraft.setScreen(new MinimapRepositionScreen(this)))
                .bounds(cx - 100, 0, 200, 20).build(), r++);

        addScroll(Button.builder(caveLabel(), b -> {
            MapSettings s = settings();
            s.caveMode = switch (s.caveMode) {
                case AUTO -> MapSettings.CaveMode.ON;
                case ON -> MapSettings.CaveMode.OFF;
                case OFF -> MapSettings.CaveMode.AUTO;
            };
            b.setMessage(caveLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);

        addScroll(Button.builder(beaconLabel(), b -> {
            settings().showBeacons = !settings().showBeacons;
            b.setMessage(beaconLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);

        addScroll(Button.builder(orbitQualityLabel(), b -> {
            settings().orbitQuality = settings().orbitQuality.next();
            b.setMessage(orbitQualityLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);

        addScroll(Button.builder(safeDropLabel(), b -> {
            MapSettings s = settings();
            int next = s.safeDropBlocks + 1;
            if (next > MapSettings.MAX_SAFE_DROP) next = MapSettings.MIN_SAFE_DROP;
            s.setSafeDropBlocks(next);
            b.setMessage(safeDropLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);

        int mobRow1 = r++;
        addScroll(Button.builder(mobLabel("Hostiles", settings().trackHostiles), b -> {
            settings().trackHostiles = !settings().trackHostiles;
            b.setMessage(mobLabel("Hostiles", settings().trackHostiles));
            persist();
        }).bounds(cx - 100, 0, 98, 20).build(), mobRow1);
        addScroll(Button.builder(mobLabel("Players", settings().trackPlayers), b -> {
            settings().trackPlayers = !settings().trackPlayers;
            b.setMessage(mobLabel("Players", settings().trackPlayers));
            persist();
        }).bounds(cx + 2, 0, 98, 20).build(), mobRow1);
        int mobRow2 = r++;
        addScroll(Button.builder(mobLabel("Passive", settings().trackPassive), b -> {
            settings().trackPassive = !settings().trackPassive;
            b.setMessage(mobLabel("Passive", settings().trackPassive));
            persist();
        }).bounds(cx - 100, 0, 98, 20).build(), mobRow2);
        addScroll(Button.builder(mobLabel("Labels", settings().mobLabels), b -> {
            settings().mobLabels = !settings().mobLabels;
            b.setMessage(mobLabel("Labels", settings().mobLabels));
            persist();
        }).bounds(cx + 2, 0, 98, 20).build(), mobRow2);

        int navRow = r++;
        addScroll(Button.builder(navLabel(), b -> {
            settings().showNavMarkers = !settings().showNavMarkers;
            b.setMessage(navLabel());
            persist();
        }).bounds(cx - 100, 0, 98, 20).build(), navRow);
        addScroll(Button.builder(depthUnitLabel(), b -> {
            settings().depthInMeters = !settings().depthInMeters;
            b.setMessage(depthUnitLabel());
            persist();
        }).bounds(cx + 2, 0, 98, 20).build(), navRow);

        int contentHeight = r * ROW_H;
        int viewport = contentBottom - CONTENT_TOP;
        maxScroll = Math.max(0, contentHeight - viewport);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        applyScroll();

        doneButton = this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent)).bounds(cx - 100, this.height - 28, 200, 20).build());
    }

    private void applyScroll() {
        for (int i = 0; i < scrollWidgets.size(); i++) {
            scrollWidgets.get(i).setY(baseY.get(i) - (int) scrollOffset);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - scrollY * ROW_H));
            applyScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
    private static Component navLabel() {
        return Component.literal("Nav: " + (settings().showNavMarkers ? "On" : "Off"));
    }
    private static Component depthUnitLabel() {
        return Component.literal("Depth: " + (settings().depthInMeters ? "Meters" : "Blocks"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Plain dark fill instead of renderBackground()'s blur — the modpack already blurs
        // once per frame, and a second blur throws "Can only blur once per frame".
        g.fill(0, 0, this.width, this.height, 0xE0101018);
        g.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);

        g.enableScissor(0, CONTENT_TOP, this.width, contentBottom);
        for (AbstractWidget w : scrollWidgets) {
            w.render(g, mouseX, mouseY, partial);
        }
        g.disableScissor();

        if (maxScroll > 0) {
            g.drawCenteredString(this.font, "scroll for more", this.width / 2, contentBottom + 2, 0xFF888888);
        }
        doneButton.render(g, mouseX, mouseY, partial);
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
