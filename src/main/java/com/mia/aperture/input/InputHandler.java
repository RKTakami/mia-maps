package com.mia.aperture.input;

import com.mia.aperture.duck.RenderDistanceTrackerDuck;
import com.mia.aperture.duck.VoxyRenderSystemDuck;
import com.mia.aperture.state.AbyssMapState;
import com.mojang.blaze3d.platform.InputConstants;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.util.AbyssUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class InputHandler {

    public static boolean onScroll(double horizontal, double vertical) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.screen == null) {
            var window = client.getWindow();
            boolean altDown = AbyssMapState.altHeld ||
                              InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) ||
                              InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);

            if (altDown) {
                if (!AbyssMapState.scrollActive) {
                    AbyssMapState.scrollActive = true;
                    // Initialize Y center to player's translated Global Y
                    var coords = AbyssUtil.toAbyss(client.player.getX(), client.player.getY());
                    AbyssMapState.scrollTargetCenterY = coords.y;
                    client.player.displayClientMessage(Component.literal("Aperture Cull: ON"), true);
                }

                // Adjust scroll target depth by 16 blocks per notch
                AbyssMapState.scrollTargetCenterY += vertical * 16.0;

                // Re-evaluate Voxy sections
                triggerReevaluation();
                return true; // Intercepted
            }
        }
        return false;
    }

    public static void triggerReevaluation() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                VoxyRenderSystem renderSystem = IGetVoxyRenderSystem.getNullable();
                if (renderSystem != null) {
                    RenderDistanceTracker tracker = ((VoxyRenderSystemDuck) renderSystem).mia$getRenderDistanceTracker();
                    if (tracker != null) {
                        ((RenderDistanceTrackerDuck) tracker).mia$reload();
                    }
                }
            }
        });
    }
}
