package com.mia.aperture.mixin;

import com.mia.aperture.client.MiaApertureModClient;
import com.mia.aperture.client.MinimapFbo;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        VoxyRenderSystem renderSystem = IGetVoxyRenderSystem.getNullable();
        if (renderSystem != null && MiaApertureModClient.minimapTextureInstance != null) {
            int textureId = MiaApertureModClient.minimapTextureInstance.getGlId();
            if (textureId != 0) {
                MinimapFbo.renderMinimap(renderSystem, camera, textureId);
            }
        }
    }
}
