package com.mia.aperture.mixin;

import com.mia.aperture.duck.RenderDistanceTrackerDuck;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.util.RingTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = RenderDistanceTracker.class, remap = false)
public abstract class RenderDistanceTrackerMixin implements RenderDistanceTrackerDuck {
    @Shadow private RingTracker tracker;
    @Shadow private int renderDistance;
    @Shadow private double posX;
    @Shadow private double posZ;

    @Override
    public void mia$reload() {
        this.tracker.unload();
        this.tracker = new RingTracker(this.tracker, this.renderDistance, ((int)this.posX)>>9, ((int)this.posZ)>>9, true);
    }
}
