package com.mia.aperture.mixin;

import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = HierarchicalOcclusionTraverser.class, remap = false)
public interface TraversalAccessor {
    @Accessor("topNodeCount")
    int mia$getTopNodeCount();
}
