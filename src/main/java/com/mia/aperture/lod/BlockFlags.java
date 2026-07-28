package com.mia.aperture.lod;

import com.mia.aperture.map.BlockColorBake;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Classifies a block <b>type</b> for the store.
 *
 * <p>Stored once per block type, not per cell, so 4096 cells share one answer.
 */
public final class BlockFlags {
    private BlockFlags() {}

    public static int of(BlockState state) {
        int f = 0;
        // OPAQUE drives coarse-level folding: when a 2x2x2 group is reduced to one cell, a block
        // that blocks sight wins over one that does not, or surfaces fill with holes when zoomed
        // out. canOcclude() is the state's own answer and needs no level or position.
        if (state.canOcclude()) f |= LodNative.FLAG_OPAQUE;

        int tint = BlockColorBake.classifyTint(state);
        if (tint == BlockColorBake.TINT_WATER) f |= LodNative.FLAG_WATER;
        if (tint == BlockColorBake.TINT_GRASS || tint == BlockColorBake.TINT_FOLIAGE) {
            f |= LodNative.FLAG_FOLIAGE;
        }
        return f;
    }
}
