package com.mia.aperture.map;

import me.cortex.voxy.common.world.other.Mapper;

public final class VoxyColorSource implements MapColorSource {
    private final BlockColorBake.Snapshot bake;
    private final BiomeTintResolver tints;

    public VoxyColorSource(BlockColorBake.Snapshot bake, BiomeTintResolver tints) {
        this.bake = bake;
        this.tints = tints;
    }

    @Override
    public int baseColor(long mappingId, Face face) {
        int blockId = Mapper.getBlockId(mappingId);
        int base = face == Face.SIDE ? bake.side(blockId) : bake.top(blockId);
        if (base == 0) return 0;
        int tintType = bake.tint(blockId);
        if (tintType == BlockColorBake.TINT_NONE) return base;
        int tint = tints.tintFor(Mapper.getBiomeId(mappingId), tintType);
        return ColorMath.tintMultiply(base, tint);
    }

    @Override
    public boolean isWater(long mappingId) {
        return bake.tint(Mapper.getBlockId(mappingId)) == BlockColorBake.TINT_WATER;
    }

    @Override
    public boolean isOpaque(long mappingId) {
        return bake.opaque(Mapper.getBlockId(mappingId));
    }
}
