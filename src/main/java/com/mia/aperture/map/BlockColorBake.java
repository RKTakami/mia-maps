package com.mia.aperture.map;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

public final class BlockColorBake {
    public static final int TINT_NONE = 0;
    public static final int TINT_GRASS = 1;
    public static final int TINT_FOLIAGE = 2;
    public static final int TINT_WATER = 3;

    private static final Direction[] HORIZONTAL =
            { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };

    private static final Set<net.minecraft.world.level.block.Block> GRASS_BLOCKS = Set.of(
            Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.TALL_GRASS,
            Blocks.LARGE_FERN, Blocks.POTTED_FERN, Blocks.BUSH);
    private static final Set<net.minecraft.world.level.block.Block> FOLIAGE_BLOCKS = Set.of(
            Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES,
            Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LEAVES, Blocks.VINE);

    private static Field originalImageField;

    private int[] topColor = new int[0];
    private int[] sideColor = new int[0];
    private byte[] tintType = new byte[0];
    private boolean[] opaque = new boolean[0];
    private int bakedCount = 0;

    // Render thread only. Bakes any blockIds Voxy registered beyond bakedCount.
    public void update(Mapper mapper) {
        int count = mapper.getBlockStateCount();
        if (count <= bakedCount) return;
        topColor = grow(topColor, count);
        sideColor = grow(sideColor, count);
        tintType = grow(tintType, count);
        opaque = grow(opaque, count);
        BlockModelShaper shaper = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper();
        for (int id = bakedCount; id < count; id++) {
            try {
                bakeOne(id, mapper, shaper);
            } catch (Throwable t) {
                topColor[id] = 0; sideColor[id] = 0; tintType[id] = TINT_NONE; opaque[id] = false;
            }
        }
        bakedCount = count;
    }

    private void bakeOne(int id, Mapper mapper, BlockModelShaper shaper) {
        BlockState state = mapper.getBlockStateFromBlockId(id);
        TextureAtlasSprite[] faces = faceSprites(shaper, state);
        int top = averageSprite(faces[0]);
        int side = averageSprite(faces[1]);

        if (state.is(Blocks.SPRUCE_LEAVES)) {
            top = ColorMath.tintMultiply(top, 0x619961);
            side = ColorMath.tintMultiply(side, 0x619961);
            tintType[id] = TINT_NONE;
        } else if (state.is(Blocks.BIRCH_LEAVES)) {
            top = ColorMath.tintMultiply(top, 0x80A755);
            side = ColorMath.tintMultiply(side, 0x80A755);
            tintType[id] = TINT_NONE;
        } else {
            tintType[id] = (byte) classifyTint(state);
        }
        topColor[id] = top;
        sideColor[id] = side;
        opaque[id] = top != 0;
    }

    public int top(int blockId) { return blockId >= 0 && blockId < bakedCount ? topColor[blockId] : 0; }
    public int side(int blockId) { return blockId >= 0 && blockId < bakedCount ? sideColor[blockId] : 0; }
    public int tint(int blockId) { return blockId >= 0 && blockId < bakedCount ? tintType[blockId] : TINT_NONE; }
    public boolean opaque(int blockId) { return blockId >= 0 && blockId < bakedCount && opaque[blockId]; }

    public void clear() {
        topColor = new int[0]; sideColor = new int[0];
        tintType = new byte[0]; opaque = new boolean[0];
        bakedCount = 0;
    }

    private static int classifyTint(BlockState state) {
        if (state.getFluidState().is(FluidTags.WATER)) return TINT_WATER;
        var block = state.getBlock();
        if (GRASS_BLOCKS.contains(block)) return TINT_GRASS;
        if (FOLIAGE_BLOCKS.contains(block)) return TINT_FOLIAGE;
        return TINT_NONE;
    }

    private static TextureAtlasSprite[] faceSprites(BlockModelShaper shaper, BlockState state) {
        TextureAtlasSprite top = null, side = null;
        BlockStateModel model = shaper.getBlockModel(state);
        List<BlockModelPart> parts = model.collectParts(RandomSource.create(42L));
        for (BlockModelPart part : parts) {
            if (top == null) {
                List<BakedQuad> up = part.getQuads(Direction.UP);
                if (!up.isEmpty()) top = up.get(0).sprite();
            }
            if (side == null) {
                for (Direction d : HORIZONTAL) {
                    List<BakedQuad> q = part.getQuads(d);
                    if (!q.isEmpty()) { side = q.get(0).sprite(); break; }
                }
            }
            if (top == null || side == null) {
                List<BakedQuad> unculled = part.getQuads(null);
                if (!unculled.isEmpty()) {
                    TextureAtlasSprite s = unculled.get(0).sprite();
                    if (top == null) top = s;
                    if (side == null) side = s;
                }
            }
            if (top != null && side != null) break;
        }
        if (top == null) top = shaper.getParticleIcon(state);
        if (side == null) side = shaper.getParticleIcon(state);
        return new TextureAtlasSprite[]{ top, side };
    }

    private static int averageSprite(TextureAtlasSprite sprite) {
        if (sprite == null) return 0;
        SpriteContents c = sprite.contents();
        int w = c.width(), h = c.height();
        if (w <= 0 || h <= 0) return 0;
        NativeImage img = originalImage(c);
        if (img == null) return 0;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                px[y * w + x] = img.getPixel(x, y); // already ARGB
        return ColorMath.alphaWeightedAverage(px);
    }

    private static NativeImage originalImage(SpriteContents c) {
        try {
            if (originalImageField == null) {
                Field f = SpriteContents.class.getDeclaredField("originalImage");
                f.setAccessible(true);
                originalImageField = f;
            }
            return (NativeImage) originalImageField.get(c);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int[] grow(int[] a, int n) { int[] b = new int[n]; System.arraycopy(a,0,b,0,a.length); return b; }
    private static byte[] grow(byte[] a, int n) { byte[] b = new byte[n]; System.arraycopy(a,0,b,0,a.length); return b; }
    private static boolean[] grow(boolean[] a, int n) { boolean[] b = new boolean[n]; System.arraycopy(a,0,b,0,a.length); return b; }
}
