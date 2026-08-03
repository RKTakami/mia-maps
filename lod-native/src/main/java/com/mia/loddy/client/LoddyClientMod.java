package com.mia.loddy.client;

import com.mia.aperture.lod.LodNative;
import com.mia.loddy.api.LodService;
import com.mia.loddy.client.render.LodWorldRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

/**
 * Client entrypoint for standalone mia-loddy mod.
 * Initializes the native LOD engine and registers the world distance renderer.
 */
public final class LoddyClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        System.out.println("[MIA Loddy] Initializing client mod...");
        LodNative.ensureLoaded();

        if (com.mia.loddy.vulkan.NativeVulkanBridge.isPanamaAvailable()) {
            WorldRenderEvents.AFTER_ENTITIES.register(context -> {
                com.mia.loddy.vulkan.render.SharedSurfaceRenderer.drawOverlay();
            });
            System.out.println("[MIA Loddy] Project Panama Zero-Copy Vulkan engine active!");
        } else if (LodNative.available()) {
            WorldRenderEvents.AFTER_ENTITIES.register(LodWorldRenderer::render);
            System.out.println("[MIA Loddy] Registered legacy JNI WorldRenderEvents.AFTER_ENTITIES renderer.");
        } else {
            System.err.println("[MIA Loddy] Native engine unavailable; distance rendering disabled.");
        }
    }
}
