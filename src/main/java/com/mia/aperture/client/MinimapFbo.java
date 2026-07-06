package com.mia.aperture.client;

import com.mia.aperture.duck.VoxyRenderSystemDuck;
import com.mia.aperture.mixin.ViewportSelectorInvoker;
import com.mia.aperture.state.AbyssMapState;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

public class MinimapFbo {
    public static final Object MIA_MAP_VIEWPORT_KEY = new Object();
    private static int fboId = 0;
    private static int depthTextureId = 0;
    private static final int SIZE = 512;

    // Voxy's pipeline skips its final blit into the target framebuffer when
    // fogParameters.environmentalEnd() < renderDistance ("fogCoversAllRendering" hack in
    // NormalRenderPipeline.finish). FogParameters.NONE has environmentalEnd == -Float.MAX_VALUE,
    // which always triggers the skip. Use +MAX_VALUE everywhere: the blit runs and the fog
    // uniforms degenerate to zero (no fog applied).
    private static final net.caffeinemc.mods.sodium.client.util.FogParameters MAP_FOG =
            new net.caffeinemc.mods.sodium.client.util.FogParameters(
                    0.0f, 0.0f, 0.0f, 0.0f,
                    Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);

    public static void ensureInitialized(int textureId) {
        if (fboId != 0) return;

        fboId = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);

        // Voxy's initDepthStencil reads the source framebuffer's depth attachment and binds it
        // as a sampler2D, so the depth attachment MUST be a texture (a renderbuffer id is not a
        // valid texture id and the stencil-priming pass would sample garbage, masking all terrain).
        // D32F with no stencil mirrors the vanilla main framebuffer Voxy normally blits into.
        depthTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTextureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, SIZE, SIZE, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTextureId, 0);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public static int getFboId() {
        return fboId;
    }

    public static void renderMinimap(VoxyRenderSystem renderSystem, Camera camera, int textureId) {
        if (textureId == 0) return;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        boolean isMapOpen = mc.screen instanceof AbyssWorldMapScreen;

        if (!isMapOpen) {
            return; // Skip Voxy rendering on HUD minimap to prevent double viewport GPU TDR
        }

        // Toggle rendering flag for the bypass Mixin
        com.mia.aperture.client.MiaApertureModClient.isRenderingMap = true;
        try {
            ensureInitialized(textureId);

            // Get vanilla player coordinates
            double px = camera.position().x;
            double py = camera.position().y;
            double pz = camera.position().z;

            int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
            int[] prevViewport = new int[4];
            glGetIntegerv(GL_VIEWPORT, prevViewport);

            glBindFramebuffer(GL_FRAMEBUFFER, fboId);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureId, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                System.err.println("[MIA Aperture] Minimap FBO incomplete, skipping map render");
                glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
                return;
            }
            glViewport(0, 0, SIZE, SIZE);

            // Depth must clear to 1.0 (Voxy's standard-Z FAR); the stencil-priming pass keeps
            // Voxy renderable only where the source depth equals FAR
            glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            glClearDepth(1.0);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            Matrix4f projection = new Matrix4f();
            Matrix4f modelView = new Matrix4f();
            
            // Base camera coordinates on vanilla coordinates instead of translated DeeperWorld coordinates
            double camX = px;
            double camY = py;
            double camZ = pz;

            if (isMapOpen) {
                float aspect = 1.0f; // 1:1 FBO Aspect Ratio
                float halfSize = 128.0f / AbyssMapState.mapZoom;
                projection.setOrtho(-halfSize * aspect, halfSize * aspect, -halfSize, halfSize, 0.05f, 2000.0f);

                if (AbyssMapState.mapPerspective == AbyssMapState.Perspective.TOP_DOWN) {
                    camX = px + AbyssMapState.mapX;
                    camY = py + 1000.0;
                    camZ = pz + AbyssMapState.mapZ;
                } else {
                    camX = px + 1000.0;
                    camY = py + AbyssMapState.mapY;
                    camZ = pz + AbyssMapState.mapZ;
                }
            } else {
                // Render HUD Minimap (Top-Down fixed layout)
                float radius = 64.0f;
                projection.setOrtho(-radius, radius, -radius, radius, 0.05f, 2000.0f);

                camX = px;
                camY = py + 1000.0;
                camZ = pz;
            }

            // Apply Voxy's internal coordinate shift on the vanilla coordinates to align with Voxy's database chunks
            int section = me.cortex.voxy.client.core.util.AbyssUtil.getSection(px);
            double shiftedCamX = camX - (double) (section << 14);
            double shiftedCamY = camY + (double) ((240 - section * 30) * 16);

            camX = shiftedCamX;
            camY = shiftedCamY;

            // Construct the modelView matrix using the shifted camera coordinates
            if (isMapOpen) {
                if (AbyssMapState.mapPerspective == AbyssMapState.Perspective.TOP_DOWN) {
                    modelView.rotateX((float) Math.toRadians(90.0))
                             .rotateY((float) Math.toRadians(180.0))
                             .translate((float) -camX, (float) -camY, (float) -camZ);
                } else {
                    modelView.rotateY((float) Math.toRadians(90.0))
                             .translate((float) -camX, (float) -camY, (float) -camZ);
                }
            } else {
                modelView.rotateX((float) Math.toRadians(90.0))
                         .rotateY((float) Math.toRadians(180.0))
                         .translate((float) -camX, (float) -camY, (float) -camZ);
            }

            ViewportSelectorInvoker selector = (ViewportSelectorInvoker) ((VoxyRenderSystemDuck) renderSystem).mia$getViewportSelector();
            Viewport<?> viewport = selector.mia$getOrCreate(MIA_MAP_VIEWPORT_KEY);

            viewport.setFogParameters(MAP_FOG);

            viewport.setVanillaProjection(projection)
                    .setProjection(projection)
                    .setModelView(modelView)
                    .setCamera(camX, camY, camZ)
                    .setScreenSize(SIZE, SIZE)
                    .update();

            // Voxy's setupViewport increments this every frame; the GPU temporal
            // visibility/traversal logic stalls if it never advances
            viewport.frameId++;

            renderSystem.renderOpaque(viewport);

            glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        } finally {
            com.mia.aperture.client.MiaApertureModClient.isRenderingMap = false;
        }
    }

    public static void shutdown() {
        if (fboId != 0) {
            glDeleteFramebuffers(fboId);
            glDeleteTextures(depthTextureId);
            fboId = 0;
            depthTextureId = 0;
        }
    }
}
