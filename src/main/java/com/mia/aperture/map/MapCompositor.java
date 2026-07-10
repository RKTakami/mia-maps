package com.mia.aperture.map;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.AbyssUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class MapCompositor {
    public static final Identifier MAP_TEXTURE = Identifier.fromNamespaceAndPath("mia_aperture_mod", "map");
    public static final Identifier HUD_TEXTURE = Identifier.fromNamespaceAndPath("mia_aperture_mod", "minimap");

    public static final int MAP_SIZE = 2048;
    public static final int HUD_SIZE = 256;
    public static final int HUD_RADIUS_BLOCKS = 96;
    public static final float OVERSAMPLE = 1.5f;
    private static final long HUD_INTERVAL_MS = 500;
    private static final long NEAR_TILE_MAX_AGE_MS = 5000;

    private static DynamicTexture mapTexture;
    private static DynamicTexture hudTexture;
    private static long lastHudCompose;
    private static final long MAP_MIN_INTERVAL_MS = 100;
    private static long lastMapSig;
    private static int lastCompletedSeen = -1;
    private static long lastMapCompose;
    private static final BlockColorBake BAKE = new BlockColorBake();
    private static BiomeTintResolver tintResolver;

    private MapCompositor() {}

    private static DynamicTexture ensure(Identifier id, DynamicTexture existing, int size) {
        if (existing != null) return existing;
        DynamicTexture tex = new DynamicTexture(id.toString(), size, size, true);
        Minecraft.getInstance().getTextureManager().register(id, tex);
        return tex;
    }

    // Fullscreen map: centerX/centerZ in WORLD coords, blocksAcrossX/Z = per-axis view span.
    // Composes immediately on a view change; rate-limits recomposes driven only by tiles
    // streaming in; idles when nothing changed.
    public static void composeMap(double centerWorldX, double centerWorldZ,
                                  int blocksAcrossX, int blocksAcrossZ, int bandTopY, int bandBottomY, MapMode mode) {
        long sig = java.util.Objects.hash((int) Math.floor(centerWorldX), (int) Math.floor(centerWorldZ),
                blocksAcrossX, blocksAcrossZ, bandTopY, bandBottomY, mode);
        int completed = MapWorker.COMPLETED.get();
        boolean viewChanged = sig != lastMapSig;
        boolean tilesChanged = completed != lastCompletedSeen;
        if (!viewChanged && !tilesChanged) return;
        long now = System.currentTimeMillis();
        if (!viewChanged && now - lastMapCompose < MAP_MIN_INTERVAL_MS) return;
        lastMapSig = sig;
        lastCompletedSeen = completed;
        lastMapCompose = now;
        mapTexture = ensure(MAP_TEXTURE, mapTexture, MAP_SIZE);
        compose(mapTexture, MAP_SIZE, centerWorldX, centerWorldZ, blocksAcrossX, blocksAcrossZ,
                bandTopY, bandBottomY, mode, 0.0);
    }

    // HUD minimap: fixed radius around the player in WORLD coords, default band
    public static void composeHud(double playerWorldX, double playerWorldZ,
                                  int bandTopY, int bandBottomY, MapMode mode, boolean round) {
        long now = System.currentTimeMillis();
        if (now - lastHudCompose < HUD_INTERVAL_MS) return;
        lastHudCompose = now;
        hudTexture = ensure(HUD_TEXTURE, hudTexture, HUD_SIZE);
        double maskRadius = round ? HUD_SIZE / (2.0 * OVERSAMPLE) : 0.0;
        compose(hudTexture, HUD_SIZE, playerWorldX, playerWorldZ, HUD_RADIUS_BLOCKS * 2, HUD_RADIUS_BLOCKS * 2,
                bandTopY, bandBottomY, mode, maskRadius);
    }

    private static void compose(DynamicTexture texture, int imageSize,
                                double centerWorldX, double centerWorldZ, int blocksAcrossX, int blocksAcrossZ,
                                int bandTopY, int bandBottomY, MapMode mode, double roundMaskRadius) {
        VoxyRenderSystem renderSystem = IGetVoxyRenderSystem.getNullable();
        var mc = Minecraft.getInstance();
        NativeImage image = texture.getPixels();
        if (renderSystem == null || mc.level == null || image == null) return;

        var engine = renderSystem.getEngine();
        var mapper = engine.getMapper();
        BAKE.update(mapper); // render thread: bake any new blockIds before the worker reads them
        if (tintResolver == null) tintResolver = new BiomeTintResolver(mapper, mc.level);
        var colors = new VoxyColorSource(BAKE.snapshot(), tintResolver);

        int sector = AbyssUtil.getSection(centerWorldX);
        int centerShiftedX = MapGeometry.shiftX((int) Math.floor(centerWorldX), sector);
        int centerShiftedZ = (int) Math.floor(centerWorldZ);

        int lvl = MapGeometry.lvlForView(Math.max(blocksAcrossX, blocksAcrossZ));
        int cellSize = 1 << lvl;
        int bandKey = MapGeometry.bandKey(bandTopY);
        double blocksPerPixelX = (double) blocksAcrossX / imageSize;
        double blocksPerPixelZ = (double) blocksAcrossZ / imageSize;

        TileKey lastKey = null;
        MapTile lastTile = null;

        for (int py = 0; py < imageSize; py++) {
            int blockZ = centerShiftedZ + (int) Math.floor((py - imageSize / 2.0) * blocksPerPixelZ);
            for (int px = 0; px < imageSize; px++) {
                int blockX = centerShiftedX + (int) Math.floor((px - imageSize / 2.0) * blocksPerPixelX);
                int argb = 0;
                // A sector (one Abyss layer) spans shifted X [-8192, 8192); pixels beyond it
                // belong to other layers and must stay empty rather than alias into their tiles
                if (blockX >= -8192 && blockX < 8192) {
                    int tx = MapGeometry.blockToTile(blockX, lvl);
                    int tz = MapGeometry.blockToTile(blockZ, lvl);
                    TileKey key = (lastKey != null && lastKey.sx() == tx && lastKey.sz() == tz)
                            ? lastKey : new TileKey(lvl, tx, tz, bandKey, mode);
                    MapTile tile;
                    if (key == lastKey) {
                        tile = lastTile;
                    } else {
                        tile = MapWorker.request(key, bandTopY, bandBottomY, engine, colors,
                                isNear(blockX, blockZ, centerShiftedX, centerShiftedZ) ? NEAR_TILE_MAX_AGE_MS : 0);
                        lastKey = key;
                        lastTile = tile;
                    }
                    if (tile != null) {
                        int span = MapGeometry.tileSpanBlocks(lvl);
                        int cx = Math.floorMod(blockX, span) / cellSize;
                        int cz = Math.floorMod(blockZ, span) / cellSize;
                        argb = tile.colors()[cz * 32 + cx];
                    }
                }
                image.setPixel(px, py, argb);
            }
        }

        if (roundMaskRadius > 0.0) {
            double c = (imageSize - 1) / 2.0;
            double r2 = roundMaskRadius * roundMaskRadius;
            for (int py2 = 0; py2 < imageSize; py2++) {
                for (int px2 = 0; px2 < imageSize; px2++) {
                    double dx = px2 - c, dy = py2 - c;
                    if (dx * dx + dy * dy > r2) {
                        image.setPixel(px2, py2, 0x00000000);
                    }
                }
            }
        }

        texture.upload();
    }

    private static boolean isNear(int x, int z, int cx, int cz) {
        return Math.abs(x - cx) <= 96 && Math.abs(z - cz) <= 96;
    }

    // Release the large fullscreen map texture (16 MB) when the map screen closes.
    // The HUD texture and bake are kept so the minimap keeps drawing. ensure()
    // recreates the map texture on the next fullscreen open.
    public static void freeMapTexture() {
        if (mapTexture != null) {
            Minecraft.getInstance().getTextureManager().release(MAP_TEXTURE);
            mapTexture = null;
        }
        lastMapSig = 0;
        lastCompletedSeen = -1;
    }

    public static void reset() {
        MapWorker.reset();
        BAKE.clear();
        tintResolver = null;
        lastCompletedSeen = -1;
    }
}
