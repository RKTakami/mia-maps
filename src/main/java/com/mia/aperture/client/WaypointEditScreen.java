package com.mia.aperture.client;

import com.mia.aperture.map.Waypoint;
import com.mia.aperture.map.WaypointColor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class WaypointEditScreen extends Screen {
    private final Screen parent;
    private final Consumer<Waypoint> onSave;
    private final String prefillName;
    private final int px, py, pz;
    private WaypointColor color;

    private EditBox nameBox, xBox, yBox, zBox;

    public WaypointEditScreen(Screen parent, Component title, String name,
                              int x, int y, int z, WaypointColor color, Consumer<Waypoint> onSave) {
        super(title);
        this.parent = parent;
        this.onSave = onSave;
        this.prefillName = name;
        this.px = x; this.py = y; this.pz = z;
        this.color = color;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4;
        this.nameBox = new EditBox(this.font, cx - 100, y, 200, 20, Component.literal("Name"));
        this.nameBox.setValue(prefillName);
        this.nameBox.setMaxLength(48);
        this.addRenderableWidget(nameBox);

        this.xBox = coordBox(cx - 100, y + 30, String.valueOf(px));
        this.yBox = coordBox(cx - 32, y + 30, String.valueOf(py));
        this.zBox = coordBox(cx + 36, y + 30, String.valueOf(pz));

        this.addRenderableWidget(Button.builder(colorLabel(), b -> {
            color = color.next();
            b.setMessage(colorLabel());
        }).bounds(cx - 100, y + 60, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(cx - 100, y + 90, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"),
                b -> this.minecraft.setScreen(parent)).bounds(cx + 4, y + 90, 96, 20).build());
    }

    private EditBox coordBox(int x, int y, String value) {
        EditBox b = new EditBox(this.font, x, y, 64, 20, Component.literal("coord"));
        b.setValue(value);
        b.setMaxLength(12);
        this.addRenderableWidget(b);
        return b;
    }

    private Component colorLabel() {
        return Component.literal("Colour: " + color);
    }

    private void save() {
        try {
            int x = Integer.parseInt(xBox.getValue().trim());
            int y = Integer.parseInt(yBox.getValue().trim());
            int z = Integer.parseInt(zBox.getValue().trim());
            String name = nameBox.getValue().trim();
            if (name.isEmpty()) name = "Waypoint";
            onSave.accept(new Waypoint(name, x, y, z, color));
            this.minecraft.setScreen(parent);
        } catch (NumberFormatException ignored) {
            // invalid coords: leave the screen open for correction
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 24, 0xFFFFFFFF);
        int cx = this.width / 2;
        int y = this.height / 4;
        g.drawString(this.font, "Name", cx - 100, y - 10, 0xFFAAAAAA);
        g.drawString(this.font, "X / Y / Z", cx - 100, y + 22, 0xFFAAAAAA);
        g.fill(cx + 82, y + 62, cx + 98, y + 78, color.argb());
    }

    @Override
    public void onClose() { this.minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
