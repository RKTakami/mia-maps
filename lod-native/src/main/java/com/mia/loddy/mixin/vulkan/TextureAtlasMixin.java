package com.mia.loddy.mixin.vulkan;

import com.mia.loddy.vulkan.NativeVulkanBridge;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public class TextureAtlasMixin {

    @Inject(method = "upload", at = @At("HEAD"), require = 0)
    private void miaLoddy$interceptAtlasUpload(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if (!NativeVulkanBridge.isPanamaAvailable() || preparations == null) {
            return;
        }

        try {
            // Signal to native Vulkan engine that the block texture atlas is ready for array layer sampling
            NativeVulkanBridge.uploadAtlas(1, 8192, 8192);
        } catch (Throwable t) {
            // Safe fallback if atlas upload is intercepted
        }
    }
}
