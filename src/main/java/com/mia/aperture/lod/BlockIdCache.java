package com.mia.aperture.lod;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Caches {@link BlockState} → store id.
 *
 * <p><b>Without this, capture would be unusable.</b> Interning goes through JNI and a database
 * lookup; a section is 4096 cells and chunks arrive tens per second, so interning per cell would
 * cost millions of round trips a minute. Cached, a solid-stone section costs one.
 *
 * <p>Keyed by identity: Minecraft's block states are canonical singletons, so reference equality is
 * both correct and considerably cheaper than hashing a state.
 *
 * <p>Not thread-safe — one instance per indexing worker.
 */
public final class BlockIdCache {
    private final long handle;
    private final Map<BlockState, Integer> ids = new IdentityHashMap<>(1024);
    private final Map<Holder<Biome>, Integer> biomeIds = new IdentityHashMap<>(64);
    private int misses;

    public BlockIdCache(long handle) {
        this.handle = handle;
    }

    /**
     * Store id for a state, interning it on first sight.
     *
     * @return the id, or {@link LodNative#AIR} if interning failed — recording air rather than
     *         mislabelled terrain, which a wrong id would be.
     */
    public int idFor(BlockState state) {
        Integer known = ids.get(state);
        if (known != null) return known;

        misses++;
        int id;
        if (state.isAir()) {
            // Air is reserved and never interned; every air state maps to the same reserved id.
            id = LodNative.AIR;
        } else {
            id = LodNative.nInternBlock(handle, BlockKeys.of(state), BlockFlags.of(state));
        }
        ids.put(state, id);
        return id;
    }

    /**
     * Store id for a biome, interning it on first sight.
     *
     * <p>Shares the block id table under a distinct prefix rather than adding a second table and a
     * second JNI call — the table is really "stable string to id", and biomes need exactly that.
     *
     * @return the id, or {@link LodNative#AIR} for an unkeyed biome, which reads as unknown.
     */
    public int idFor(Holder<Biome> biome) {
        if (biome == null) return LodNative.AIR;
        Integer known = biomeIds.get(biome);
        if (known != null) return known;
        int id = biome.unwrapKey()
                .map(k -> LodNative.nInternBlock(handle, LodNative.BIOME_PREFIX + k.identifier(), 0))
                .orElse(LodNative.AIR);
        biomeIds.put(biome, id);
        return id;
    }

    /** How many distinct states have been interned. Diagnostics — an unexpectedly large number
     *  means block-state properties are fragmenting the table. */
    public int distinctStates() {
        return misses;
    }

    /** Distinct biomes seen. One, over a large area, means we are reading a default rather than
     *  real data — biome tint would then be uniformly wrong in a way that looks plausible. */
    public int distinctBiomes() {
        return biomeIds.size();
    }

    /** Names of the biomes seen, for diagnosing the above. */
    public java.util.List<String> biomeNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Holder<Biome> h : biomeIds.keySet()) {
            h.unwrapKey().ifPresent(k -> out.add(k.identifier().toString()));
        }
        java.util.Collections.sort(out);
        return out;
    }
}
