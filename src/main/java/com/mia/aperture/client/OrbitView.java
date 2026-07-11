package com.mia.aperture.client;

import com.mia.aperture.map.OrbitCamera;
import com.mia.aperture.map.OrbitScene;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class OrbitView extends Screen {
    private double yaw = 45, pitch = 30, zoom = 1.0;
    private final Screen parent;

    public OrbitView(Screen parent) {
        super(Component.literal("Abyss 3D"));
        this.parent = parent;
    }

    private OrbitCamera camera() {
        var p = this.minecraft != null ? this.minecraft.player : null;
        double fx = p != null ? p.getX() : 0, fy = p != null ? p.getY() : 0, fz = p != null ? p.getZ() : 0;
        double dist = 160 * zoom;
        return new OrbitCamera(fx, fy, fz, yaw, pitch, dist);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF0B0B10);
        if (this.minecraft != null && this.minecraft.player != null) {
            OrbitScene.render(camera(), zoom);
            int s = Math.min(this.width, this.height);
            int x0 = (this.width - s) / 2, y0 = (this.height - s) / 2;
            guiGraphics.blit(OrbitScene.TEXTURE, x0, y0, s, s, 0.0f, 1.0f, 0.0f, 1.0f);
        }
        guiGraphics.drawString(this.font, "Abyss 3D  —  drag: orbit   scroll: zoom   Esc: close", 8, 8, 0xFFFFFFFF);
        guiGraphics.drawString(this.font,
                String.format("yaw %.0f  pitch %.0f  zoom %.2fx", yaw, pitch, zoom), 8, 20, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        yaw += dragX * 0.4;
        pitch = Math.max(-89, Math.min(89, pitch + dragY * 0.4));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        zoom *= verticalAmount > 0 ? 0.85 : 1.18;
        zoom = Math.max(0.15, Math.min(6.0, zoom));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
