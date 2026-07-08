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
    public static final int HUD_SIZE = 128;
    private static final int HUD_RADIUS_BLOCKS = 64;
    private static final long HUD_INTERVAL_MS = 500;
    private static final long NEAR_TILE_MAX_AGE_MS = 5000;

    private static DynamicTexture mapTexture;
    private static DynamicTexture hudTexture;
    private static long lastHudCompose;
    private static long lastMapSig;
    private static int lastCompletedSeen;
    private static final BlockColorBake BAKE = new BlockColorBake();
    private static BiomeTintResolver tintResolver;

    private MapCompositor() {}

    private static DynamicTexture ensure(Identifier id, DynamicTexture existing, int size) {
        if (existing != null) return existing;
        DynamicTexture tex = new DynamicTexture(id.toString(), size, size, true);
        Minecraft.getInstance().getTextureManager().register(id, tex);
        return tex;
    }

    // Fullscreen map: centerX/centerZ in WORLD coords, blocksAcross = view span.
    // Composes only when a compose input changed or a requested tile has completed.
    public static void composeMap(double centerWorldX, double centerWorldZ,
                                  int blocksAcross, int bandTopY, int bandBottomY, MapMode mode) {
        long sig = java.util.Objects.hash((int) Math.floor(centerWorldX), (int) Math.floor(centerWorldZ),
                blocksAcross, bandTopY, bandBottomY, mode);
        int completed = MapWorker.COMPLETED.get();
        if (sig == lastMapSig && completed == lastCompletedSeen) return;
        lastMapSig = sig;
        lastCompletedSeen = completed;
        mapTexture = ensure(MAP_TEXTURE, mapTexture, MAP_SIZE);
        compose(mapTexture, MAP_SIZE, centerWorldX, centerWorldZ, blocksAcross,
                bandTopY, bandBottomY, mode);
    }

    // HUD minimap: fixed radius around the player in WORLD coords, default band
    public static void composeHud(double playerWorldX, double playerWorldZ,
                                  int bandTopY, int bandBottomY, MapMode mode) {
        long now = System.currentTimeMillis();
        if (now - lastHudCompose < HUD_INTERVAL_MS) return;
        lastHudCompose = now;
        hudTexture = ensure(HUD_TEXTURE, hudTexture, HUD_SIZE);
        compose(hudTexture, HUD_SIZE, playerWorldX, playerWorldZ, HUD_RADIUS_BLOCKS * 2,
                bandTopY, bandBottomY, mode);
    }

    private static void compose(DynamicTexture texture, int imageSize,
                                double centerWorldX, double centerWorldZ, int blocksAcross,
                                int bandTopY, int bandBottomY, MapMode mode) {
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

        int lvl = MapGeometry.lvlForView(blocksAcross);
        int cellSize = 1 << lvl;
        int bandKey = MapGeometry.bandKey(bandTopY);
        double blocksPerPixel = (double) blocksAcross / imageSize;

        TileKey lastKey = null;
        MapTile lastTile = null;

        for (int py = 0; py < imageSize; py++) {
            int blockZ = centerShiftedZ + (int) Math.floor((py - imageSize / 2.0) * blocksPerPixel);
            for (int px = 0; px < imageSize; px++) {
                int blockX = centerShiftedX + (int) Math.floor((px - imageSize / 2.0) * blocksPerPixel);
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
        texture.upload();
    }

    private static boolean isNear(int x, int z, int cx, int cz) {
        return Math.abs(x - cx) <= 96 && Math.abs(z - cz) <= 96;
    }

    public static void reset() {
        MapWorker.reset();
        BAKE.clear();
        tintResolver = null;
    }
}
