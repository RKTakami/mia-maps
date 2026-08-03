package com.mia.loddy.mixin.vulkan;

import com.mia.loddy.vulkan.NativeVulkanBridge;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockModelShaper.class)
public class BlockModelShaperMixin {

    @Inject(method = "rebuildCache", at = @At("RETURN"), require = 0)
    private void miaLoddy$interceptBlockTextureMapping(CallbackInfo ci) {
        if (!NativeVulkanBridge.isPanamaAvailable()) return;

        try {
            BlockModelShaper shaper = (BlockModelShaper) (Object) this;
            int maxStateId = 20000;
            int[] stateToLayerMap = new int[maxStateId];

            for (int stateId = 0; stateId < maxStateId; stateId++) {
                BlockState state = Block.stateById(stateId);
                if (state == null || state.isAir()) {
                    stateToLayerMap[stateId] = 0; // Default / air
                    continue;
                }

                TextureAtlasSprite sprite = shaper.getParticleIcon(state);
                if (sprite != null && sprite.contents() != null) {
                    String name = sprite.contents().name().toString();
                    // Simple deterministic hash/mapping for texture array layer ID (0 .. 255)
                    int layerId = Math.abs(name.hashCode()) % 256;
                    stateToLayerMap[stateId] = layerId;
                }
            }

            NativeVulkanBridge.uploadBlockLayerLookup(stateToLayerMap);
        } catch (Throwable t) {
            // Safe fallback if block models aren't ready
        }
    }
}
