package com.mia.aperture.lod;

import com.mia.aperture.map.BiomeTintResolver;
import com.mia.aperture.map.BlockColorBake;
import com.mia.aperture.map.ColorMath;
import com.mia.aperture.map.Face;
import com.mia.aperture.map.MapColorSource;

/**
 * Colour for terrain read from the LOD store.
 *
 * <p>Implements the same interface the map already consumes, so nothing downstream needs to know
 * which data path produced a cell — which is what makes the two comparable.
 *
 * <p>A mapping id packs both halves the map needs: block id in the low 32 bits, biome id in the
 * high. Storing them separately would mean widening {@link MapColorSource}, and packing is what the
 * existing path does too, so the tile renderer stays untouched.
 */
public final class LodColorSource implements MapColorSource {
    private final BlockColorBake.Snapshot bake;
    private final BiomeTintResolver tints;

    public LodColorSource(BlockColorBake.Snapshot bake, BiomeTintResolver tints) {
        this.bake = bake;
        this.tints = tints;
    }

    /** Pack a block and biome id into the long the map passes around. */
    public static long mappingId(int blockId, int biomeId) {
        return ((long) biomeId << 32) | (blockId & 0xFFFFFFFFL);
    }

    public static int blockOf(long mappingId) {
        return (int) (mappingId & 0xFFFFFFFFL);
    }

    public static int biomeOf(long mappingId) {
        return (int) (mappingId >>> 32);
    }

    @Override
    public int baseColor(long mappingId, Face face) {
        int blockId = blockOf(mappingId);
        int base = face == Face.SIDE ? bake.side(blockId) : bake.top(blockId);
        if (base == 0) return 0;
        int tintType = bake.tint(blockId);
        if (tintType == BlockColorBake.TINT_NONE) return base;
        return ColorMath.tintMultiply(base, tints.tintFor(biomeOf(mappingId), tintType));
    }

    @Override
    public boolean isWater(long mappingId) {
        return bake.tint(blockOf(mappingId)) == BlockColorBake.TINT_WATER;
    }

    @Override
    public boolean isOpaque(long mappingId) {
        return bake.opaque(blockOf(mappingId));
    }
}
