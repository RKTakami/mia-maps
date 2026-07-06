package com.mia.aperture.mixin;

import com.mia.aperture.client.AbyssWorldMapScreen;
import com.mia.aperture.client.MiaApertureModClient;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = me.cortex.voxy.client.mixin.minecraft.MixinLevelRenderer.class, remap = false)
public class LevelRendererVoxyBypassMixin {

    @Inject(method = "voxy$getRenderSystem", at = @At("HEAD"), cancellable = true)
    private void mia$bypassVoxyMainRender(CallbackInfoReturnable<VoxyRenderSystem> cir) {
        if (Minecraft.getInstance().screen instanceof AbyssWorldMapScreen && !MiaApertureModClient.isRenderingMap) {
            cir.setReturnValue(null);
        }
    }
}
