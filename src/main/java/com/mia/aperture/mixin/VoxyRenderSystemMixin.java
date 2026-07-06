package com.mia.aperture.mixin;

import com.mia.aperture.duck.VoxyRenderSystemDuck;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class VoxyRenderSystemMixin implements VoxyRenderSystemDuck {
    @Shadow private RenderDistanceTracker renderDistanceTracker;
    @Shadow private ViewportSelector<?> viewportSelector;

    @Override
    public RenderDistanceTracker mia$getRenderDistanceTracker() {
        return this.renderDistanceTracker;
    }

    @Override
    public ViewportSelector<?> mia$getViewportSelector() {
        return this.viewportSelector;
    }
}
