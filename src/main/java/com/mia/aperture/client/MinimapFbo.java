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
    private static long lastDiagTime = 0;
    private static final java.nio.ByteBuffer DIAG_PIXELS = org.lwjgl.BufferUtils.createByteBuffer(SIZE * SIZE * 4);

    private static void drainGlErrors(String stage) {
        int err;
        while ((err = glGetError()) != GL_NO_ERROR) {
            System.out.println("[MIA Aperture diag] GL error 0x" + Integer.toHexString(err) + " at " + stage);
        }
    }

    private static int mia$reflectTexId(Object pipeline, String fieldName) {
        try {
            for (Class<?> cls = pipeline.getClass(); cls != null; cls = cls.getSuperclass()) {
                for (var field : cls.getDeclaredFields()) {
                    if (field.getName().equalsIgnoreCase(fieldName)) {
                        field.setAccessible(true);
                        Object tex = field.get(pipeline);
                        if (tex == null) return 0;
                        return (int) tex.getClass().getField("id").get(tex);
                    }
                }
            }
            return -2;
        } catch (Throwable t) {
            System.out.println("[MIA Aperture diag] reflect " + fieldName + " threw: " + t);
            return -1;
        }
    }

    private static String mia$sampleRgba(int texId) {
        if (texId <= 0) return "tex:" + texId;
        DIAG_PIXELS.clear();
        DIAG_PIXELS.limit(4);
        org.lwjgl.opengl.GL45.glGetTextureSubImage(texId, 0, SIZE / 2, SIZE / 2, 0, 1, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, DIAG_PIXELS);
        return (DIAG_PIXELS.get(0) & 0xFF) + "/" + (DIAG_PIXELS.get(1) & 0xFF) + "/" + (DIAG_PIXELS.get(2) & 0xFF) + "/" + (DIAG_PIXELS.get(3) & 0xFF);
    }

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
                // Generous depth range: an ortho map projection has no perspective cost, and a
                // tight near/far is a frustum-cull risk for Voxy's GPU traversal
                projection.setOrtho(-halfSize * aspect, halfSize * aspect, -halfSize, halfSize, -16000.0f, 16000.0f);

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
                projection.setOrtho(-radius, radius, -radius, radius, -16000.0f, 16000.0f);

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

            // Voxy downloads per-LOD-layer traversal statistics when this is enabled;
            // they appear as HTC/HRS lines in the addDebugInfo dump below
            me.cortex.voxy.client.RenderStatistics.enabled = true;

            boolean diag = System.currentTimeMillis() - lastDiagTime > 1000;
            if (diag) {
                lastDiagTime = System.currentTimeMillis();
                drainGlErrors("pre-renderOpaque");
                System.out.println("[MIA Aperture diag] begin pass | frameId=" + viewport.frameId
                        + " | cam=" + String.format("%.1f/%.1f/%.1f", camX, camY, camZ)
                        + " raw=" + String.format("%.1f/%.1f/%.1f", px, py, pz)
                        + " section=" + section
                        + " | zoom=" + AbyssMapState.mapZoom
                        + " persp=" + AbyssMapState.mapPerspective);
            }

            // Voxy never clears its internal colour buffer (its own composite hides stale
            // pixels via depth discard); clear it so the direct copy below shows only what
            // this frame actually drew
            try {
                var pipeline = ((VoxyRenderSystemDuck) renderSystem).mia$getPipeline();
                try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    org.lwjgl.opengl.GL45.nglClearNamedFramebufferfv(pipeline.fb.framebuffer.id, GL_COLOR, 0,
                            org.lwjgl.system.MemoryUtil.memAddress(stack.floats(0.0f, 0.0f, 0.0f, 0.0f)));
                }
            } catch (Throwable t) {
                if (diag) System.out.println("[MIA Aperture diag] internal clear threw: " + t);
            }

            renderSystem.renderOpaque(viewport);

            // Diagnostic composite: copy Voxy's internal colour buffer straight into our FBO,
            // bypassing Voxy's fog-gated final blit and its alpha/depth discards, so the map
            // shows exactly what Voxy painted
            try {
                var pipeline = ((VoxyRenderSystemDuck) renderSystem).mia$getPipeline();
                org.lwjgl.opengl.GL45.glBlitNamedFramebuffer(pipeline.fb.framebuffer.id, fboId,
                        0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            } catch (Throwable t) {
                if (diag) System.out.println("[MIA Aperture diag] direct blit threw: " + t);
            }

            if (diag) {
                System.out.println("[MIA Aperture diag] renderOpaque returned");
                drainGlErrors("post-renderOpaque");
                // glFinish is a pure GPU sync: if the queued work from renderOpaque is what
                // kills the driver, we die here and the log proves it without any readback
                System.out.println("[MIA Aperture diag] syncing (glFinish)");
                glFinish();
                System.out.println("[MIA Aperture diag] glFinish ok");
                try {
                    var dbg = new java.util.ArrayList<String>();
                    renderSystem.addDebugInfo(dbg);
                    for (String line : dbg) {
                        System.out.println("[MIA Aperture diag][voxy] " + line);
                    }
                } catch (Throwable t) {
                    System.out.println("[MIA Aperture diag] addDebugInfo threw: " + t);
                }
                try {
                    // Stage counters. drawCountCallBuffer layout (prep.comp): dispatchX/Y/Z,
                    // opaqueDrawCount, translucentDrawCount, temporalOpaqueDrawCount
                    int[] one = new int[1];
                    org.lwjgl.opengl.GL45.glGetNamedBufferSubData(viewport.getRenderList().id, 0, one);
                    int renderListCount = one[0];
                    int[] counts = new int[6];
                    if (viewport instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport mdicViewport) {
                        org.lwjgl.opengl.GL45.glGetNamedBufferSubData(mdicViewport.drawCountCallBuffer.id, 0, counts);
                    }
                    System.out.println("[MIA Aperture diag] renderList=" + renderListCount
                            + " opaqueDraws=" + counts[3] + " translucentDraws=" + counts[4] + " temporalDraws=" + counts[5]);
                } catch (Throwable t) {
                    System.out.println("[MIA Aperture diag] counters threw: " + t);
                }
                try {
                    // Peek all three internal surfaces at the centre: raw colour, post-SSAO
                    // colour (what the final blit actually samples), and depth (the blit
                    // discards where depth is exactly 0 or 1)
                    var pipeline = ((VoxyRenderSystemDuck) renderSystem).mia$getPipeline();
                    int rawTex = mia$reflectTexId(pipeline, "colourTex");
                    int ssaoTex = mia$reflectTexId(pipeline, "colourSSAOTex");
                    int depthTexId = pipeline.fb.getDepthTex().id;
                    String raw = mia$sampleRgba(rawTex);
                    String ssao = mia$sampleRgba(ssaoTex);
                    DIAG_PIXELS.clear();
                    DIAG_PIXELS.limit(4);
                    org.lwjgl.opengl.GL45.glGetTextureSubImage(depthTexId, 0, SIZE / 2, SIZE / 2, 0, 1, 1, 1,
                            org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, GL_FLOAT, DIAG_PIXELS);
                    float centerDepth = DIAG_PIXELS.getFloat(0);
                    int touched = 0;
                    for (int gy = 0; gy < 8; gy++) {
                        for (int gx = 0; gx < 8; gx++) {
                            DIAG_PIXELS.clear();
                            DIAG_PIXELS.limit(4);
                            org.lwjgl.opengl.GL45.glGetTextureSubImage(depthTexId, 0, gx * 64 + 32, gy * 64 + 32, 0, 1, 1, 1,
                                    org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, GL_FLOAT, DIAG_PIXELS);
                            if (DIAG_PIXELS.getFloat(0) < 1.0f) touched++;
                        }
                    }
                    System.out.println("[MIA Aperture diag] center raw=" + raw + " ssao=" + ssao + " depth=" + centerDepth
                            + " depthCoverage=" + touched + "/64");
                } catch (Throwable t) {
                    System.out.println("[MIA Aperture diag] surface peek threw: " + t);
                }
                try {
                    // Peek Voxy's INTERNAL colour buffer: distinguishes "terrain drawn internally
                    // but blit to our FBO fails" from "nothing drawn at all"
                    var pipeline = ((VoxyRenderSystemDuck) renderSystem).mia$getPipeline();
                    int internalFb = pipeline.fb.framebuffer.id;
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, internalFb);
                    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
                    int lit = 0;
                    int[][] points = {{SIZE / 2, SIZE / 2}, {SIZE / 4, SIZE / 4}, {3 * SIZE / 4, SIZE / 4}, {SIZE / 4, 3 * SIZE / 4}, {3 * SIZE / 4, 3 * SIZE / 4}};
                    int centerA = -1;
                    for (int[] pt : points) {
                        DIAG_PIXELS.clear();
                        DIAG_PIXELS.limit(4);
                        glReadPixels(pt[0], pt[1], 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, DIAG_PIXELS);
                        if ((DIAG_PIXELS.get(3) & 0xFF) != 0) lit++;
                        if (pt[0] == SIZE / 2) centerA = DIAG_PIXELS.get(3) & 0xFF;
                    }
                    System.out.println("[MIA Aperture diag] INTERNAL colour samples lit: " + lit + "/5 centerAlpha=" + centerA);
                } catch (Throwable t) {
                    System.out.println("[MIA Aperture diag] internal peek threw: " + t);
                }
                try {
                    // Full-size glReadPixels access-violates inside atio6axx.dll (AMD GL driver);
                    // sample a handful of 1x1 reads instead, which are proven safe on this driver
                    glBindFramebuffer(GL_FRAMEBUFFER, fboId);
                    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
                    int lit = 0;
                    int[][] points = {{SIZE / 2, SIZE / 2}, {SIZE / 4, SIZE / 4}, {3 * SIZE / 4, SIZE / 4}, {SIZE / 4, 3 * SIZE / 4}, {3 * SIZE / 4, 3 * SIZE / 4}};
                    for (int[] pt : points) {
                        DIAG_PIXELS.clear();
                        DIAG_PIXELS.limit(4);
                        glReadPixels(pt[0], pt[1], 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, DIAG_PIXELS);
                        if ((DIAG_PIXELS.get(3) & 0xFF) != 0) lit++;
                    }
                    drainGlErrors("post-readback");
                    System.out.println("[MIA Aperture diag] OUR FBO sample points lit: " + lit + "/" + points.length);
                } catch (Throwable t) {
                    System.out.println("[MIA Aperture diag] readback threw: " + t);
                }
            }

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
