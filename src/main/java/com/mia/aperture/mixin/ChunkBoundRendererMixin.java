package com.mia.aperture.mixin;

import com.mia.aperture.client.MiaApertureModClient;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkBoundRenderer.class, remap = false)
public class ChunkBoundRendererMixin {

    // The chunk-bound pass writes vanilla terrain depth so Voxy is culled where vanilla
    // chunks cover the view — correct in-world, but the map draws no vanilla chunks, so
    // it punches a hole in the middle of the map covering the whole vanilla render
    // distance. Skip it for the map viewport and clear the bound to "nothing occludes"
    // (0.0 = inverseClearDepth for Voxy's standard-Z properties).
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void mia$skipForMapViewport(Viewport<?> viewport, CallbackInfo ci) {
        if (MiaApertureModClient.isRenderingMap) {
            // The terrain shader discards fragments closer than this bound, which is how the
            // map cuts off layers above the player's band (see MinimapFbo)
            viewport.depthBoundingBuffer.clear(com.mia.aperture.state.AbyssMapState.mapFragmentDepthBound);
            ci.cancel();
        }
    }
}
