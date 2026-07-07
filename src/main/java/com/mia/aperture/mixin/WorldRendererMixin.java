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

    @Inject(method = "renderLevel", at = @At("HEAD"))
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
                try {
                    var duck = (com.mia.aperture.duck.VoxyRenderSystemDuck) renderSystem;
                    var selector = (me.cortex.voxy.client.core.rendering.ViewportSelector<?>) duck.mia$getViewportSelector();
                    var mainViewport = selector.getViewport();
                    int mainRenderList = -1;
                    if (mainViewport != null) {
                        int[] one = new int[1];
                        org.lwjgl.opengl.GL45.glGetNamedBufferSubData(mainViewport.getRenderList().id, 0, one);
                        mainRenderList = one[0];
                    }
                    int topNodes = ((TraversalAccessor) (Object) duck.mia$getTraversal()).mia$getTopNodeCount();
                    System.out.println("[MIA Aperture diag] world: mainRenderList=" + mainRenderList + " topNodes=" + topNodes);
                    var stats = new java.util.ArrayList<String>();
                    me.cortex.voxy.client.RenderStatistics.addDebug(stats);
                    for (String line : stats) {
                        System.out.println("[MIA Aperture diag] world " + line);
                    }
                } catch (Throwable t) {
                    System.out.println("[MIA Aperture diag] world counters threw: " + t);
                }
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
