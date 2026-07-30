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
    // 3D Area and 3D Quality each change what the OTHER one reports (area sets the detail level,
    // quality sets how much area fits), so changing either has to refresh both labels.
    private AbstractWidget orbitQualityButton;
    private AbstractWidget orbitAreaSlider;
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
        exportArmed = false;
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

        addScroll(Button.builder(renderModeLabel(), b -> {
            com.mia.aperture.state.AbyssMapState.mapRenderMode =
                    AbyssWorldMapScreen.nextRenderMode(com.mia.aperture.state.AbyssMapState.mapRenderMode);
            b.setMessage(renderModeLabel());
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


        addScroll(Button.builder(beaconLabel(), b -> {
            settings().showBeacons = !settings().showBeacons;
            b.setMessage(beaconLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);

        orbitQualityButton = Button.builder(orbitQualityLabel(), b -> {
            settings().orbitQuality = settings().orbitQuality.next();
            b.setMessage(orbitQualityLabel());
            if (orbitAreaSlider != null) orbitAreaSlider.setMessage(orbitAreaLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build();
        addScroll(orbitQualityButton, r++);

        addScroll(Button.builder(transparencyLabel(), b -> {
            settings().orbitTransparency = MapSettings.nextTransparency(settings().orbitTransparency);
            b.setMessage(transparencyLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);
        int transferRow = r++;
        importButton = addScroll(Button.builder(importLabel(), b -> {
            com.mia.aperture.lod.StoreTransferJob.startImport();
        }).bounds(cx - 100, 0, 98, 20).build(), transferRow);
        // Two clicks, because this one WRITES to Voxy's live database. It only fills gaps and never
        // overwrites, but a single misclick starting a bulk write to someone else's store is not a
        // reasonable thing to build.
        exportButton = addScroll(Button.builder(exportLabel(), b -> {
            if (!exportArmed) {
                exportArmed = true;
                b.setMessage(exportLabel());
                return;
            }
            exportArmed = false;
            com.mia.aperture.lod.StoreTransferJob.startExport();
        }).bounds(cx + 2, 0, 98, 20).build(), transferRow);

        addScroll(Button.builder(mapSourceLabel(), b -> {
            settings().mapFromStore = !settings().mapFromStore;
            b.setMessage(mapSourceLabel());
            // The two sources number cells differently, so cached tiles from one cannot be read as
            // the other. TileKey separates them, but clearing makes the switch visible immediately
            // rather than as tiles happen to expire.
            com.mia.aperture.map.MapWorker.reset();
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);
        addScroll(Button.builder(worldRenderLabel(), b -> {
            settings().lodWorldRender = !settings().lodWorldRender;
            b.setMessage(worldRenderLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);
        addScroll(Button.builder(probeLabel(), b -> {
            settings().lodDistanceProbe = !settings().lodDistanceProbe;
            b.setMessage(probeLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);
        addScroll(Button.builder(gpuRenderLabel(), b -> {
            settings().gpuRender = !settings().gpuRender;
            b.setMessage(gpuRenderLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);

        orbitAreaSlider = new AbstractSliderButton(cx - 100, 0, 200, 20,
                orbitAreaLabel(), orbitAreaToValue(settings().orbitAreaBlocks)) {
            @Override protected void updateMessage() { setMessage(orbitAreaLabel()); }
            @Override protected void applyValue() {
                int n = MapSettings.ORBIT_AREA_STEPS.length;
                int idx = (int) Math.round(this.value * (n - 1));
                settings().setOrbitAreaBlocks(MapSettings.ORBIT_AREA_STEPS[idx]);
                if (orbitQualityButton != null) orbitQualityButton.setMessage(orbitQualityLabel());
            }
        };
        addScroll(orbitAreaSlider, r++);

        addScroll(Button.builder(orbitStatsLabel(), b -> {
            settings().orbitStats = !settings().orbitStats;
            b.setMessage(orbitStatsLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);

        // Voxy's own setting, not ours: ingestion fills the LOD database this mod reads, and the
        // MIA modpack ships it OFF, which shows up as a completely blank map.
        addScroll(Button.builder(ingestLabel(), b -> {
            Boolean on = VoxyIngest.enabled();
            if (on != null) VoxyIngest.setEnabled(!on);
            b.setMessage(ingestLabel());
        }).bounds(cx - 100, 0, 200, 20).build(), r++);

        addScroll(Button.builder(safeDropLabel(), b -> {
            MapSettings s = settings();
            int next = s.safeDropBlocks + 1;
            if (next > MapSettings.MAX_SAFE_DROP) next = MapSettings.MIN_SAFE_DROP;
            s.setSafeDropBlocks(next);
            b.setMessage(safeDropLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);

        addScroll(Button.builder(maxSurvivableDropLabel(), b -> {
            MapSettings s = settings();
            int next = s.maxSurvivableDrop + 2;
            if (next > MapSettings.MAX_SURVIVABLE_DROP) next = MapSettings.MIN_SURVIVABLE_DROP;
            s.setMaxSurvivableDrop(next);
            b.setMessage(maxSurvivableDropLabel());
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
        int mobRow3 = r++;
        addScroll(Button.builder(mobLabel("Nearby List", settings().mobList), b -> {
            settings().mobList = !settings().mobList;
            b.setMessage(mobLabel("Nearby List", settings().mobList));
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), mobRow3);

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

    private static Component renderModeLabel() {
        return Component.literal("Map mode: " + com.mia.aperture.state.AbyssMapState.mapRenderMode);
    }
    private static Component shapeLabel() {
        return Component.literal("Frame: " + (settings().shape == MapSettings.FrameShape.SQUARE
                ? "Square" : "Round"));
    }
    private static Component sizeLabel() {
        return Component.literal("Minimap size: " + settings().minimapSize + "px");
    }
    private static Component beaconLabel() {
        return Component.literal("Waypoint beacons: " + (settings().showBeacons ? "On" : "Off"));
    }
    // Reports the voxel size the current Area + Quality pair actually produces, which is what governs
    // whether the view reads as terrain — the texture size never did.
    private static Component orbitQualityLabel() {
        MapSettings.OrbitQuality q = settings().orbitQuality;
        int area = settings().orbitAreaBlocks;
        if (area == MapSettings.ORBIT_AREA_WHOLE) {
            return Component.literal("3D Quality: " + q.label + " (" + q.textureSize + "px)");
        }
        com.mia.aperture.map.OrbitLod.Plan p =
                com.mia.aperture.map.OrbitLod.planForArea(area, q.gpuGrid, com.mia.aperture.map.OrbitLod.MAX_LEVEL, q.maxCells);
        return Component.literal("3D Quality: " + q.label + " (" + p.cellBlocks() + "-blk voxels)");
    }
    private static double orbitAreaToValue(int blocks) {
        int[] steps = MapSettings.ORBIT_AREA_STEPS;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == blocks) return i / (double) (steps.length - 1);
        }
        return 1.0 / (steps.length - 1); // fall back to the 2048 step
    }
    // Shows the coverage actually sampled. The grid budget can cap it below the requested area, and
    // that used to happen silently — the view simply mapped less ground than the number promised.
    private static Component orbitAreaLabel() {
        int b = settings().orbitAreaBlocks;
        if (b == MapSettings.ORBIT_AREA_WHOLE) return Component.literal("3D Area: Whole Abyss");
        com.mia.aperture.map.OrbitLod.Plan p = com.mia.aperture.map.OrbitLod.planForArea(
                b, settings().orbitQuality.gpuGrid, com.mia.aperture.map.OrbitLod.MAX_LEVEL,
                settings().orbitQuality.maxCells);
        // Warn only when the budget cannot reach the area the user asked for. Coverage above `b` is
        // normal — the extra is frustum headroom, not a shortfall.
        return Component.literal(p.coverageBlocks() < b
                ? "3D Area: " + b + " -> only " + p.coverageBlocks()
                : "3D Area: " + b + " blocks");
    }
    private static Component orbitStatsLabel() {
        return Component.literal("3D Stats: " + (settings().orbitStats ? "On" : "Off"));
    }

    private static Component transparencyLabel() {
        int t = settings().orbitTransparency;
        return Component.literal("Cave Maps: " + (t == 0 ? "Off" : t + "%"));
    }

    private static boolean exportArmed;
    /** Whether WE hid the cursor, so we only ever restore what we changed. */
    private boolean cursorHidden;
    private AbstractWidget importButton;
    private AbstractWidget exportButton;

    private static Component importLabel() {
        return Component.literal(com.mia.aperture.lod.StoreTransferJob.busy()
                ? "Working..." : "Import from Voxy");
    }

    private static Component exportLabel() {
        if (com.mia.aperture.lod.StoreTransferJob.busy()) return Component.literal("Working...");
        return Component.literal(exportArmed ? "Confirm export?" : "Export to Voxy");
    }

    private static Component mapSourceLabel() {
        return Component.literal("Map Source: "
                + (settings().mapFromStore ? "mia-loddy store" : "Voxy"));
    }

    private static Component worldRenderLabel() {
        return Component.literal("LOD World Render: "
                + (settings().lodWorldRender ? "On" : "Off"));
    }

    private static Component probeLabel() {
        return Component.literal("LOD Distance Probe: "
                + (settings().lodDistanceProbe ? "On" : "Off"));
    }

    private static Component gpuRenderLabel() {
        return Component.literal("3D GPU Renderer: " + (settings().gpuRender ? "On" : "Off"));
    }
    private static Component ingestLabel() {
        Boolean on = VoxyIngest.enabled();
        if (on == null) return Component.literal("Voxy map data: unavailable");
        return Component.literal("Voxy map data (ingest): " + (on ? "On" : "OFF - no map!"));
    }
    private static Component safeDropLabel() {
        return Component.literal("Safe fall distance: " + settings().safeDropBlocks + " blocks");
    }
    private static Component maxSurvivableDropLabel() {
        return Component.literal("Max survivable drop: " + settings().maxSurvivableDrop + " blocks");
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
        // A brass instrument case around the settings, and an engraved nameplate instead of plain
        // white text. The panel is drawn before the widgets so it sits behind them.
        int caseX = Math.max(8, this.width / 2 - 150);
        int caseW = Math.min(this.width - 16, 300);
        int caseY = CONTENT_TOP - 8, caseH = contentBottom - CONTENT_TOP + 16;
        SteamTheme.panel(g, caseX, caseY, caseW, caseH);
        // Gearing at the bottom corners of the case, outside the button column so it never sits under
        // a control's hit area.
        SteamTheme.gearCluster(g, caseX - 10, caseY + caseH - 14, 7);
        SteamTheme.ornamentGear(g, caseX + caseW + 12, caseY + caseH - 10, 7, true);
        SteamTheme.nameplate(g, this.font, this.title.getString(), this.width / 2, 14);
        // Gears beside the title while a transfer runs. "Working..." sitting still looks identical to
        // "Working..." that has hung, so movement is the part that says the job is alive.
        //
        // Placed here rather than near the buttons on purpose: the row under the content is already
        // occupied by "scroll for more" and the result line, and drawing into an occupied row is
        // exactly how the earlier notice ended up invisible under the controls text.
        // showActivity, not busy: the gears carry on briefly after the work ends so a one-second
        // transfer registers as something rather than flickering. The button labels still use busy(),
        // because one reading "Working..." with nothing running would simply be false.
        boolean active = com.mia.aperture.lod.StoreTransferJob.showActivity();
        if (active) {
            int titleRight = this.width / 2 + this.font.width(this.title) / 2;
            SteamGear.draw(g, titleRight + 16, 23, 7);
        }
        // Gears in place of the pointer while work runs. Reconciled EVERY frame from busy() rather
        // than toggled on transitions: a hidden cursor that fails to come back is a genuinely
        // unpleasant thing to leave behind, and reconciling means any missed transition — an
        // exception mid-transfer, a screen swapped out from under us — self-corrects next frame
        // instead of persisting.
        setCursorHidden(active);
        if (active) SteamGear.drawOne(g, mouseX, mouseY, 6);

        // Keep the transfer buttons honest. They used to be set to "Importing..." on click and never
        // changed back, so they asserted something false for the rest of the session — and there was
        // no way to tell a finished transfer from one still running.
        if (importButton != null) importButton.setMessage(importLabel());
        if (exportButton != null) exportButton.setMessage(exportLabel());

        // Last outcome, on screen rather than only in the log. Wrapped near the bottom so a long
        // line stays readable.
        String result = com.mia.aperture.lod.StoreTransferJob.lastResult;
        if (result != null) {
            int y = contentBottom + 16;
            // Wrapped on whitespace by measured width. font.split returns FormattedCharSequence,
            // which drawCenteredString does not accept, and guessing at that API is how two earlier
            // rendering rounds were lost today.
            int max = this.width - 40;
            StringBuilder line = new StringBuilder();
            for (String word : result.split(" ")) {
                String probe = line.length() == 0 ? word : line + " " + word;
                if (this.font.width(probe) > max && line.length() > 0) {
                    g.drawCenteredString(this.font, line.toString(), this.width / 2, y, 0xFF88DDFF);
                    y += 10;
                    line.setLength(0);
                    line.append(word);
                } else {
                    line.setLength(0);
                    line.append(probe);
                }
            }
            if (line.length() > 0) {
                g.drawCenteredString(this.font, line.toString(), this.width / 2, y, 0xFF88DDFF);
            }
        }

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
        // Never leave a bulk write to Voxy one click away across screens.
        exportArmed = false;
        // Belt and braces on the cursor: the per-frame reconcile covers the normal path, but this
        // screen can be removed without another frame ever running.
        setCursorHidden(false);
        persist();
    }

    /**
     * Hide or show the system pointer, tracking whether the change was ours.
     *
     * <p>Only touches GLFW when the state actually differs, so this neither fights other mods for the
     * input mode every frame nor restores a cursor it did not hide.
     */
    private void setCursorHidden(boolean hidden) {
        if (hidden == cursorHidden) return;
        var window = this.minecraft != null ? this.minecraft.getWindow() : null;
        if (window == null) return;
        org.lwjgl.glfw.GLFW.glfwSetInputMode(window.handle(), org.lwjgl.glfw.GLFW.GLFW_CURSOR,
                hidden ? org.lwjgl.glfw.GLFW.GLFW_CURSOR_HIDDEN
                       : org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL);
        cursorHidden = hidden;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
