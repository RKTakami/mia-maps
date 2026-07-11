package com.mia.aperture.map;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.AbyssUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class OrbitScene {
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("mia_aperture_mod", "orbit");
    public static final int SIZE = 768;
    private static final double FOV = Math.toRadians(70.0);
    private static final int EXTENT = 128;   // sampled cube edge (blocks) at zoom 1
    private static final int G_MAX = 128;    // max grid cells per axis (bounds memory + time)
    private static final int MAX_POINTS = 40000;
    private static final int MAX_RADIUS = 6; // max half-size of a plotted voxel splat

    private static DynamicTexture texture;
    private static float[] depthBuf;
    private static long lastCameraSig = Long.MIN_VALUE;
    private static List<VoxelCloud.Point> cloud;
    private static long cloudSig = Long.MIN_VALUE;

    private OrbitScene() {}

    public static void reset() {
        cloud = null;
        cloudSig = lastCameraSig = Long.MIN_VALUE;
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
            texture = null;
        }
    }

    // Render-thread. `cam` focus is the player WORLD position; returns the texture to blit.
    public static Identifier render(OrbitCamera cam, double zoom) {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        Minecraft mc = Minecraft.getInstance();
        if (rs == null || mc.level == null) return TEXTURE;
        MapColorSource colors = MapCompositor.colorSource();
        if (colors == null) return TEXTURE;
        var engine = rs.getEngine();

        int extent = Math.max(16, (int) Math.round(EXTENT * zoom));
        int lvl = 0;
        while ((extent >> lvl) > G_MAX && lvl < MapGeometry.MAX_LVL) lvl++;

        // Voxy indexes sections in shifted (per-layer) X; sample + orbit in that space.
        int sector = AbyssUtil.getSection(cam.focusX);
        int shiftedFocusX = MapGeometry.shiftX((int) Math.floor(cam.focusX), sector);
        int focusY = (int) Math.floor(cam.focusY);
        int focusZ = (int) Math.floor(cam.focusZ);

        long cs = Objects.hash(shiftedFocusX, focusY, focusZ, extent, lvl);
        if (cloud == null || cs != cloudSig) {
            cloud = VoxelCloud.sample(engine, colors, shiftedFocusX, focusY, focusZ, extent, lvl, MAX_POINTS);
            cloudSig = cs;
        }

        if (texture == null) {
            texture = new DynamicTexture(TEXTURE.toString(), SIZE, SIZE, true);
            mc.getTextureManager().register(TEXTURE, texture);
        }
        long camSig = Objects.hash(cs, (int) Math.round(cam.yawDeg), (int) Math.round(cam.pitchDeg),
                (int) Math.round(cam.distance));
        if (camSig != lastCameraSig) {
            rasterize(cam, shiftedFocusX);
            texture.upload();
            lastCameraSig = camSig;
        }
        return TEXTURE;
    }

    private static void rasterize(OrbitCamera worldCam, int shiftedFocusX) {
        NativeImage img = texture.getPixels();
        if (img == null || cloud == null) return;
        OrbitCamera cam = new OrbitCamera(shiftedFocusX, worldCam.focusY, worldCam.focusZ,
                worldCam.yawDeg, worldCam.pitchDeg, worldCam.distance);
        double focal = (SIZE / 2.0) / Math.tan(FOV / 2.0);
        if (depthBuf == null) depthBuf = new float[SIZE * SIZE];
        Arrays.fill(depthBuf, Float.MAX_VALUE);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) img.setPixel(x, y, 0x00000000);
        }
        double[] cel = cam.cameraPos();
        double[] b = cam.basis();
        for (VoxelCloud.Point p : cloud) {
            BeaconGeometry.Screen s = BeaconGeometry.project(p.x() - cel[0], p.y() - cel[1], p.z() - cel[2],
                    b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], focal, SIZE, SIZE);
            if (!s.onScreen()) continue;
            int r = Math.max(1, Math.min(MAX_RADIUS, (int) Math.round(focal * p.cellSize() / s.depth() / 2.0)));
            plot(img, s.x(), s.y(), r, (float) s.depth(), 0xFF000000 | (p.argb() & 0xFFFFFF));
        }
    }

    private static void plot(NativeImage img, int cx, int cy, int r, float z, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int py = cy + dy;
            if (py < 0 || py >= SIZE) continue;
            for (int dx = -r; dx <= r; dx++) {
                int px = cx + dx;
                if (px < 0 || px >= SIZE) continue;
                int di = py * SIZE + px;
                if (z >= depthBuf[di]) continue;
                depthBuf[di] = z;
                img.setPixel(px, py, color);
            }
        }
    }
}
