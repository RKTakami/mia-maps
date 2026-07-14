package com.mia.aperture.client;

import com.mia.aperture.map.Waypoint;
import com.mia.aperture.map.WaypointColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class WaypointListScreen extends Screen {
    private final Screen parent;

    public WaypointListScreen(Screen parent) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
    }

    private static String key() {
        return com.mia.aperture.map.WaypointStore.currentServerKey(Minecraft.getInstance());
    }

    private static List<Waypoint> list() {
        return MiaApertureModClient.waypoints.list(key());
    }

    private static void persist() {
        com.mia.aperture.map.WaypointConfig.save(MiaApertureModClient.waypointConfigPath(), MiaApertureModClient.waypoints);
    }

    private static Component markersLabel() {
        return Component.literal("Markers: " + (MiaApertureModClient.mapSettings.showNavMarkers ? "On" : "Off"));
    }

    private static Component visLabel(Waypoint w) {
        return Component.literal(w.visible ? "On" : "Off");
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = 40;
        List<Waypoint> wps = list();
        for (int i = 0; i < wps.size() && i < 8; i++) {
            final int index = i;
            int rowY = top + i * 24;
            this.addRenderableWidget(Button.builder(visLabel(wps.get(i)), b -> {
                Waypoint wp = list().get(index);
                wp.visible = !wp.visible;
                b.setMessage(visLabel(wp));
                persist();
            }).bounds(cx - 190, rowY, 30, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Share"),
                    b -> WaypointChat.share(list().get(index)))
                    .bounds(cx + 54, rowY, 44, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Edit"), b -> edit(index))
                    .bounds(cx + 100, rowY, 40, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                list().remove(index);
                persist();
                this.rebuildWidgets();
            }).bounds(cx + 142, rowY, 46, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Go"), b -> {
                Waypoint w = list().get(index);
                com.mia.aperture.map.RouteService.setDestination(w.x + 0.5, w.y + 0.5, w.z + 0.5);
                this.minecraft.setScreen(parent);
            }).bounds(cx + 190, rowY, 36, 20).build());
        }

        this.addRenderableWidget(Button.builder(markersLabel(), b -> {
            MiaApertureModClient.mapSettings.showNavMarkers = !MiaApertureModClient.mapSettings.showNavMarkers;
            b.setMessage(markersLabel());
            com.mia.aperture.map.MapConfig.save(MiaApertureModClient.mapConfigPath(), MiaApertureModClient.mapSettings);
        }).bounds(cx - 100, this.height - 100, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Stop Routing"),
                b -> com.mia.aperture.map.RouteService.clear())
                .bounds(cx - 100, this.height - 76, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Add"), b ->
                this.minecraft.setScreen(new WaypointEditScreen(this, Component.literal("New Waypoint"),
                        "Waypoint", 0, 0, 0, WaypointColor.RED, w -> { list().add(w); persist(); })))
                .bounds(cx - 100, this.height - 52, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent)).bounds(cx + 4, this.height - 52, 96, 20).build());
    }

    private void edit(int index) {
        List<Waypoint> wps = list();
        if (index < 0 || index >= wps.size()) return;
        Waypoint w = wps.get(index);
        this.minecraft.setScreen(new WaypointEditScreen(this, Component.literal("Edit Waypoint"),
                w.name, w.x, w.y, w.z, w.color, nw -> { list().set(index, nw); persist(); }));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        int cx = this.width / 2;
        int top = 40;
        List<Waypoint> wps = list();
        if (wps.isEmpty()) {
            g.drawCenteredString(this.font, "No waypoints yet — Add one, or press B in-world.",
                    cx, top + 4, 0xFFAAAAAA);
        }
        for (int i = 0; i < wps.size() && i < 8; i++) {
            Waypoint w = wps.get(i);
            int rowY = top + i * 24;
            g.fill(cx - 156, rowY + 4, cx - 146, rowY + 16, w.color.argb());
            String name = this.font.plainSubstrByWidth(w.name, 190);
            g.drawString(this.font, name, cx - 142, rowY + 6, w.visible ? 0xFFFFFFFF : 0xFF777777);
        }
    }

    @Override
    public void onClose() { this.minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
