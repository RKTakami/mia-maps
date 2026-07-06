package com.mia.aperture.mixin;

import com.mia.aperture.state.AbyssMapState;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardMixin {

    // Since the 1.21.9+ input rework, live glfwGetKey polling (InputConstants.isKeyDown) is
    // unreliable for modifier checks; vanilla now carries modifier bits on the input events.
    // Track Alt state from key events so scroll handlers can read it.
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void mia$trackAltState(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (event.key() == GLFW.GLFW_KEY_LEFT_ALT || event.key() == GLFW.GLFW_KEY_RIGHT_ALT) {
            AbyssMapState.altHeld = action != GLFW.GLFW_RELEASE;
            System.out.println("[MIA Aperture diag] alt key event on " + Thread.currentThread().getName()
                    + ": action=" + action + " -> altHeld=" + AbyssMapState.altHeld);
        } else {
            AbyssMapState.altHeld = event.hasAltDown();
        }
    }
}
