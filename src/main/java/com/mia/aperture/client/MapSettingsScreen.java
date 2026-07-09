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

        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, y + 80, 200, 20).build());
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
