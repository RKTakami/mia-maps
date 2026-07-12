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
    public static final int SIZE = 2048;
    private static final double FOV = Math.toRadians(70.0);
    private static final int EXTENT = 128;    // sampled cube edge (blocks) at zoom 1
    private static final int G_MAX = 128;     // max grid cells per axis (bounds memory + time)
    private static final int MAX_POINTS = 120000;
    private static final int MAX_RADIUS = 40; // max half-size of a plotted voxel splat (fills gaps up close)
    private static final float SATURATION = 1.25f; // colour punch (map uses 1.15 + slope shading)
    private static final float CONTRAST = 1.08f;
    // World-fixed "sun" from above and slightly to one side; ambient keeps shadowed faces lit.
    private static final float LX = 0.321f, LY = 0.919f, LZ = 0.230f;
    private static final float AMBIENT = 0.4f;

    private static DynamicTexture texture;
    private static float[] depthBuf;
    private static long lastCameraSig = Long.MIN_VALUE;
    private static List<VoxelCloud.Point> cloud;
    private static long cloudSig = Long.MIN_VALUE;

    private OrbitScene() {}

    public static int lastCloudSize() { return cloud == null ? 0 : cloud.size(); }

    // Camera-space depth of the nearest rasterized voxel at texture pixel (sx,sy), or
    // +inf if empty/out of range. Comparable to a marker's project(...).depth(). For
    // occluding HUD markers behind terrain.
    public static float depthAt(int sx, int sy) {
        if (depthBuf == null || sx < 0 || sy < 0 || sx >= SIZE || sy >= SIZE) return Float.MAX_VALUE;
        return depthBuf[sy * SIZE + sx];
    }

    // Camera snapshot from the last rasterize, so HUD overlays project through the SAME
    // camera as the cloud (in texture space, SIZE x SIZE).
    private static double[] hudCel, hudB;
    private static double hudFocal, hudFx, hudFy, hudFz;

    // Project a focus-relative offset through the cloud camera -> texture-space Screen.
    public static BeaconGeometry.Screen projectHud(double ox, double oy, double oz) {
        if (hudB == null) return new BeaconGeometry.Screen(false, SIZE / 2, SIZE / 2, 0, 0, 0);
        return BeaconGeometry.project(hudFx + ox - hudCel[0], hudFy + oy - hudCel[1], hudFz + oz - hudCel[2],
                hudB[0], hudB[1], hudB[2], hudB[3], hudB[4], hudB[5], hudB[6], hudB[7], hudB[8], hudFocal, SIZE, SIZE);
    }

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

        // Voxy indexes sections in shifted (per-layer) X and shifted Y; sample + orbit in
        // that space (Z is unshifted). Keep the EXACT (fractional) focus for the camera so
        // the orbit pivots on the true player position; use floored ints only for the voxel
        // grid. (Pivoting on the floored block made the player sweep a sub-block circle.)
        int sector = AbyssUtil.getSection(cam.focusX);
        double focusXExact = cam.focusX - (double) (sector << 14);
        double focusYExact = cam.focusY + (240 - sector * 30) * 16.0;
        double focusZExact = cam.focusZ;
        int shiftedFocusX = (int) Math.floor(focusXExact);
        int shiftedFocusY = (int) Math.floor(focusYExact);
        int focusZ = (int) Math.floor(focusZExact);

        long cs = Objects.hash(shiftedFocusX, shiftedFocusY, focusZ, extent, lvl);
        if (cloud == null || cs != cloudSig) {
            cloud = VoxelCloud.sample(engine, colors, shiftedFocusX, shiftedFocusY, focusZ, extent, lvl, MAX_POINTS);
            cloudSig = cs;
        }

        if (texture == null) {
            texture = new DynamicTexture(TEXTURE.toString(), SIZE, SIZE, true);
            mc.getTextureManager().register(TEXTURE, texture);
        }
        long camSig = Objects.hash(cs, (int) Math.round(cam.yawDeg), (int) Math.round(cam.pitchDeg),
                (int) Math.round(cam.distance));
        if (camSig != lastCameraSig) {
            rasterize(cam, focusXExact, focusYExact, focusZExact);
            texture.upload();
            lastCameraSig = camSig;
        }
        return TEXTURE;
    }

    private static void rasterize(OrbitCamera worldCam, double focusX, double focusY, double focusZ) {
        NativeImage img = texture.getPixels();
        if (img == null || cloud == null) return;
        OrbitCamera cam = new OrbitCamera(focusX, focusY, focusZ,
                worldCam.yawDeg, worldCam.pitchDeg, worldCam.distance);
        double focal = (SIZE / 2.0) / Math.tan(FOV / 2.0);
        if (depthBuf == null) depthBuf = new float[SIZE * SIZE];
        Arrays.fill(depthBuf, Float.MAX_VALUE);
        img.fillRect(0, 0, SIZE, SIZE, 0x00000000);
        double[] cel = cam.cameraPos();
        double[] b = cam.basis();
        hudCel = cel; hudB = b; hudFocal = focal; hudFx = focusX; hudFy = focusY; hudFz = focusZ;
        for (VoxelCloud.Point p : cloud) {
            BeaconGeometry.Screen s = BeaconGeometry.project(p.x() - cel[0], p.y() - cel[1], p.z() - cel[2],
                    b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], focal, SIZE, SIZE);
            if (!s.onScreen()) continue;
            int r = Math.max(1, Math.min(MAX_RADIUS, (int) Math.round(focal * p.cellSize() / s.depth() / 2.0)));
            float ndotl = Math.max(0f, p.nx() * LX + p.ny() * LY + p.nz() * LZ);
            float light = AMBIENT + (1f - AMBIENT) * ndotl;
            int col = ColorMath.shade(ColorMath.punch(p.argb(), SATURATION, CONTRAST), light);
            plot(img, s.x(), s.y(), r, (float) s.depth(), 0xFF000000 | (col & 0xFFFFFF));
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
