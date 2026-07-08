package com.mia.aperture.mixin;

import com.mia.aperture.duck.VoxyRenderSystemDuck;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class VoxyRenderSystemMixin implements VoxyRenderSystemDuck {
    @Shadow private RenderDistanceTracker renderDistanceTracker;

    @Override
    public RenderDistanceTracker mia$getRenderDistanceTracker() {
        return this.renderDistanceTracker;
    }
}
