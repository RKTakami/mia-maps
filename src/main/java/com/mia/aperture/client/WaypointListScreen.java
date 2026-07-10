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

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = 40;
        List<Waypoint> wps = list();
        for (int i = 0; i < wps.size() && i < 8; i++) {
            final int index = i;
            int rowY = top + i * 24;
            this.addRenderableWidget(Button.builder(Component.literal("Share"),
                    b -> WaypointChat.share(list().get(index)))
                    .bounds(cx + 26, rowY, 46, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Edit"), b -> edit(index))
                    .bounds(cx + 74, rowY, 46, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                list().remove(index);
                persist();
                this.rebuildWidgets();
            }).bounds(cx + 122, rowY, 46, 20).build());
        }

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
            g.fill(cx - 100, rowY + 4, cx - 88, rowY + 16, w.color.argb());
            g.drawString(this.font, w.name + "  (" + w.x + " " + w.y + " " + w.z + ")",
                    cx - 82, rowY + 6, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() { this.minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
