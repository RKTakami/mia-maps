package com.mia.aperture.client;

import com.mia.aperture.map.MapConfig;
import com.mia.aperture.map.MapSettings;
import com.mia.aperture.map.MinimapLayout;
import com.mia.aperture.map.MinimapRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class MinimapRepositionScreen extends Screen {
    private static final int MARGIN = com.mia.aperture.map.MinimapLayout.MARGIN;
    private final Screen parent;

    public MinimapRepositionScreen(Screen parent) {
        super(Component.literal("Reposition Minimap"));
        this.parent = parent;
    }

    private static MapSettings settings() {
        return MiaApertureModClient.mapSettings;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, "Drag the minimap to reposition it", this.width / 2, 20, 0xFFFFFFFF);
        if (this.minecraft.player != null) {
            var s = settings();
            int size = s.minimapSize;
            int x = MinimapLayout.originX(s.minimapX, this.width, size, MARGIN);
            int y = MinimapLayout.originY(s.minimapY, this.height, size, MARGIN);
            MinimapRenderer.draw(g, this.minecraft.player, x, y, size, s);
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        var s = settings();
        int size = s.minimapSize;
        double topLeftX = event.x() - size / 2.0;
        double topLeftY = event.y() - size / 2.0;
        double fx = MinimapLayout.fractionFromPixelX((int) Math.round(topLeftX), this.width, size, MARGIN);
        double fy = MinimapLayout.fractionFromPixelY((int) Math.round(topLeftY), this.height, size, MARGIN);
        s.setMinimapPos(fx, fy);
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        MapConfig.save(MiaApertureModClient.mapConfigPath(), settings());
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
