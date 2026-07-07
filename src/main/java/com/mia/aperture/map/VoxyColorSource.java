package com.mia.aperture.map;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.ConcurrentHashMap;

public final class VoxyColorSource implements MapColorSource {
    private final Mapper mapper;
    private final BlockGetter level;
    private final ConcurrentHashMap<Integer, int[]> blockInfo = new ConcurrentHashMap<>();

    public VoxyColorSource(Mapper mapper, BlockGetter level) {
        this.mapper = mapper;
        this.level = level;
    }

    // info[0] = base ARGB (0 for air/none), info[1] = 1 if water, info[2] = 1 if opaque.
    // Deliberate tradeoff: getMapColor runs off-thread with BlockPos.ZERO, so biome-tinted
    // blocks get a uniform tint and any concurrent-read failure caches as colorless;
    // acceptable for map colors, verified in-game
    private int[] info(long mappingId) {
        int blockId = Mapper.getBlockId(mappingId);
        return this.blockInfo.computeIfAbsent(blockId, id -> {
            if (id == 0) return new int[]{0, 0, 0};
            try {
                BlockState state = this.mapper.getBlockStateFromBlockId(id);
                int col = state.getMapColor(this.level, BlockPos.ZERO).col;
                boolean water = state.getFluidState().is(FluidTags.WATER);
                boolean opaque = this.mapper.getBlockStateOpacity(mappingId) > 0 || water;
                int argb = col == 0 ? 0 : 0xFF000000 | col;
                return new int[]{argb, water ? 1 : 0, opaque ? 1 : 0};
            } catch (Throwable t) {
                System.err.println("[MIA Aperture] color lookup failed for block id " + id + ": " + t);
                return new int[]{0, 0, 0};
            }
        });
    }

    @Override
    public int baseColor(long mappingId) {
        return info(mappingId)[0];
    }

    @Override
    public boolean isWater(long mappingId) {
        return info(mappingId)[1] == 1;
    }

    @Override
    public boolean isOpaque(long mappingId) {
        return info(mappingId)[2] == 1 && info(mappingId)[0] != 0;
    }
}
