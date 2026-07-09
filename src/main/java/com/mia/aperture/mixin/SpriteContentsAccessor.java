package com.mia.aperture.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Reflection by the dev-mapping name ("originalImage") fails at runtime because the
// shipped client uses intermediary field names and loom cannot remap reflection string
// literals. A mixin @Accessor is refmap-processed, so the field name is remapped at build.
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("originalImage")
    NativeImage mia$getOriginalImage();
}
