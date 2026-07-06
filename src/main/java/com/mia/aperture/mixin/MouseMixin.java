package com.mia.aperture.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mia.aperture.input.InputHandler;

@Mixin(MouseHandler.class)
public class MouseMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onScrollInject(long window, double xoffset, double yoffset, CallbackInfo ci) {
        if (InputHandler.onScroll(xoffset, yoffset)) {
            ci.cancel();
        }
    }
}
