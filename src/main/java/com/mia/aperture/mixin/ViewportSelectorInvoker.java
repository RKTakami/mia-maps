package com.mia.aperture.mixin;

import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ViewportSelector.class, remap = false)
public interface ViewportSelectorInvoker {
    @Invoker("getOrCreate")
    Viewport<?> mia$getOrCreate(Object holder);
}
