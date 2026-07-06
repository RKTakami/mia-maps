package com.mia.aperture.client;

import com.mia.aperture.duck.VoxyRenderSystemDuck;
import com.mia.aperture.mixin.ViewportSelectorInvoker;
import com.mia.aperture.state.AbyssMapState;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.AbyssUtil;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

public class MinimapFbo {
    public static final Object MIA_MAP_VIEWPORT_KEY = new Object();
    private static int fboId = 0;
    private static int depthBufferId = 0;
    private static final int SIZE = 512;

    public static void ensureInitialized(int textureId) {
        if (fboId != 0) return;

        fboId = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);

        depthBufferId = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthBufferId);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, SIZE, SIZE);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthBufferId);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("Failed to create Minimap FBO!");
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public static int getFboId() {
        return fboId;
    }

    public static void renderMinimap(VoxyRenderSystem renderSystem, Camera camera, int textureId) {
        if (textureId == 0) return;

        // Fetch fog first to avoid NullPointerExceptions
        Viewport<?> mainViewport = ((VoxyRenderSystemDuck) renderSystem).mia$getViewportSelector().getViewport();
        net.caffeinemc.mods.sodium.client.util.FogParameters fog = null;
        if (mainViewport != null && mainViewport.fogParameters != null) {
            com.mia.aperture.client.MiaApertureModClient.lastKnownFog = mainViewport.fogParameters;
            fog = mainViewport.fogParameters;
        } else if (com.mia.aperture.client.MiaApertureModClient.lastKnownFog != null) {
            fog = com.mia.aperture.client.MiaApertureModClient.lastKnownFog;
        }

        if (fog == null) {
            return; // Skip rendering if fog parameters are not initialized yet
        }

        ensureInitialized(textureId);

        // Get player coordinates
        double px = camera.position().x;
        double py = camera.position().y;
        double pz = camera.position().z;

        AbyssUtil.Coords abyssCoords = AbyssUtil.toAbyss(px, py);
        double ax = abyssCoords.x;
        double ay = abyssCoords.y;

        int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);

        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureId, 0);
        glViewport(0, 0, SIZE, SIZE);

        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        Matrix4f projection = new Matrix4f();
        Matrix4f modelView = new Matrix4f();
        double camX = ax;
        double camY = ay;
        double camZ = pz;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        boolean isMapOpen = mc.screen instanceof AbyssWorldMapScreen;

        if (isMapOpen) {
            float aspect = 1.0f; // 1:1 FBO Aspect Ratio
            float halfSize = 128.0f / AbyssMapState.mapZoom;
            projection.setOrtho(-halfSize * aspect, halfSize * aspect, -halfSize, halfSize, 0.05f, 2000.0f);

            if (AbyssMapState.mapPerspective == AbyssMapState.Perspective.TOP_DOWN) {
                camX = ax + AbyssMapState.mapX;
                camY = ay + 1000.0;
                camZ = pz + AbyssMapState.mapZ;

                modelView.rotateX((float) Math.toRadians(90.0))
                         .rotateY((float) Math.toRadians(180.0))
                         .translate((float) -camX, (float) -camY, (float) -camZ);
            } else {
                camX = ax + 1000.0;
                camY = ay + AbyssMapState.mapY;
                camZ = pz + AbyssMapState.mapZ;

                modelView.rotateY((float) Math.toRadians(90.0))
                         .translate((float) -camX, (float) -camY, (float) -camZ);
            }
        } else {
            // Render HUD Minimap (Top-Down fixed layout)
            float radius = 64.0f;
            projection.setOrtho(-radius, radius, -radius, radius, 0.05f, 2000.0f);

            camY = ay + 1000.0;
            modelView.rotateX((float) Math.toRadians(90.0))
                     .rotateY((float) Math.toRadians(180.0))
                     .translate((float) -ax, (float) -camY, (float) -pz);
        }

        ViewportSelectorInvoker selector = (ViewportSelectorInvoker) ((VoxyRenderSystemDuck) renderSystem).mia$getViewportSelector();
        Viewport<?> viewport = selector.mia$getOrCreate(MIA_MAP_VIEWPORT_KEY);

        viewport.setFogParameters(fog);

        viewport.setVanillaProjection(projection)
                .setProjection(projection)
                .setModelView(modelView)
                .setCamera(camX, camY, camZ)
                .setScreenSize(SIZE, SIZE)
                .update();

        renderSystem.renderOpaque(viewport);

        glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
        glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
    }

    public static void shutdown() {
        if (fboId != 0) {
            glDeleteFramebuffers(fboId);
            glDeleteRenderbuffers(depthBufferId);
            fboId = 0;
            depthBufferId = 0;
        }
    }
}
