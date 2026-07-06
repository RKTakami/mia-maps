package com.mia.aperture.mixin;

import com.mia.aperture.client.MiaApertureModClient;
import com.mia.aperture.client.MinimapFbo;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {
    private static long lastMixinLogTime = 0;

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void onRenderLevelHead(
            com.mojang.blaze3d.resource.GraphicsResourceAllocator resourceAllocator,
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            Matrix4f matrix4f,
            Matrix4f matrix4f2,
            Matrix4f matrix4f3,
            com.mojang.blaze3d.buffers.GpuBufferSlice gpuBufferSlice,
            org.joml.Vector4f vector4f,
            boolean bl,
            CallbackInfo ci
    ) {
        VoxyRenderSystem renderSystem = IGetVoxyRenderSystem.getNullable();

        if (renderSystem != null) {
            MiaApertureModClient.ensureTextureInitialized();

            long now = System.currentTimeMillis();
            if (now - lastMixinLogTime > 5000) {
                lastMixinLogTime = now;
                System.out.println("[MIA Aperture debug] onRenderLevelHead: renderSystem=" + (renderSystem != null) 
                    + ", minimapTextureInstance=" + (MiaApertureModClient.minimapTextureInstance != null)
                    + ", textureId=" + (MiaApertureModClient.minimapTextureInstance != null ? MiaApertureModClient.minimapTextureInstance.getGlId() : 0));
            }

            if (MiaApertureModClient.minimapTextureInstance != null) {
                int textureId = MiaApertureModClient.minimapTextureInstance.getGlId();
                if (textureId != 0) {
                    MinimapFbo.renderMinimap(renderSystem, camera, textureId);
                }
            }
        }
    }
}
