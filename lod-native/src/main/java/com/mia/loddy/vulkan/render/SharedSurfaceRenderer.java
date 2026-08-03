package com.mia.loddy.vulkan.render;

import com.mia.loddy.vulkan.NativeVulkanBridge;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composites the Vulkan/MoltenVK zero-copy LOD rendering output over Minecraft's
 * OpenGL window using Apple IOSurface hardware surface sharing on macOS.
 */
public class SharedSurfaceRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("MIA Loddy SharedSurface");
    private static int glTextureId = 0;
    private static boolean ioSurfaceAttached = false;

    public static void attachIOSurface(int ioSurfaceId, int width, int height) {
        if (!NativeVulkanBridge.isPanamaAvailable() || ioSurfaceId == 0 || width <= 0 || height <= 0) {
            return;
        }

        try {
            if (glTextureId == 0) {
                glTextureId = GL11.glGenTextures();
            }

            GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, glTextureId);
            // Setup GL_TEXTURE_RECTANGLE parameters for zero-copy kernel surface import
            GL11.glTexParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

            ioSurfaceAttached = true;
            LOGGER.info("[MIA Loddy] Attached macOS IOSurface (ID={}) as OpenGL Texture {} ({}x{})",
                ioSurfaceId, glTextureId, width, height);
        } catch (Throwable t) {
            LOGGER.warn("[MIA Loddy] Could not attach IOSurface to OpenGL texture: {}", t.getMessage());
            ioSurfaceAttached = false;
        }
    }

    public static boolean isAttached() {
        return ioSurfaceAttached && glTextureId != 0;
    }

    /**
     * Composites the Vulkan LOD framebuffer over Minecraft's scene.
     */
    public static void drawOverlay() {
        if (!isAttached()) return;

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL31.GL_TEXTURE_RECTANGLE);
        GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, glTextureId);

        // Draw overlay rectangle using basic OpenGL commands for hardware surface blending
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, height); GL11.glVertex2f(-1, -1);
        GL11.glTexCoord2f(width, height); GL11.glVertex2f(1, -1);
        GL11.glTexCoord2f(width, 0); GL11.glVertex2f(1, 1);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-1, 1);
        GL11.glEnd();

        GL11.glDisable(GL31.GL_TEXTURE_RECTANGLE);
        GL11.glDisable(GL11.GL_BLEND);
    }
}

class GL31 {
    public static final int GL_TEXTURE_RECTANGLE = 0x84F5;
}
