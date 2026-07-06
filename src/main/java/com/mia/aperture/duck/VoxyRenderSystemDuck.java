package com.mia.aperture.duck;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.ViewportSelector;

public interface VoxyRenderSystemDuck {
    RenderDistanceTracker mia$getRenderDistanceTracker();
    ViewportSelector<?> mia$getViewportSelector();
    AbstractRenderPipeline mia$getPipeline();
    me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser mia$getTraversal();
}
