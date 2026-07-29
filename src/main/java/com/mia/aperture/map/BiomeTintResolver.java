package com.mia.aperture.map;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.concurrent.ConcurrentHashMap;

public final class BiomeTintResolver {
    private static final int DEFAULT_GRASS = 0x91BD59;
    private static final int DEFAULT_FOLIAGE = 0x77AB2F;
    private static final int DEFAULT_WATER = 0x3F76E4;

    /** id -> canonical biome name, e.g. "minecraft:plains". The only thing that differs between
     *  data paths; everything below it is shared so the two cannot tint differently. */
    private final java.util.function.IntFunction<String> nameForId;
    private final Level level;
    // biomeId -> {grass, foliage, water}
    private final ConcurrentHashMap<Integer, int[]> cache = new ConcurrentHashMap<>();

    public BiomeTintResolver(Mapper mapper, Level level) {
        this(id -> {
            Mapper.BiomeEntry[] entries = mapper.getBiomeEntries();
            return id >= 0 && id < entries.length ? entries[id].biome : null;
        }, level);
    }

    public BiomeTintResolver(java.util.function.IntFunction<String> nameForId, Level level) {
        this.nameForId = nameForId;
        this.level = level;
    }

    public int tintFor(int biomeId, int tintType) {
        int[] t = cache.computeIfAbsent(biomeId, this::resolve);
        return switch (tintType) {
            case BlockColorBake.TINT_GRASS -> t[0];
            case BlockColorBake.TINT_FOLIAGE -> t[1];
            case BlockColorBake.TINT_WATER -> t[2];
            default -> 0xFFFFFF;
        };
    }

    private int[] resolve(int biomeId) {
        try {
            String name = nameForId.apply(biomeId);
            if (name == null) return defaults();
            Identifier id = Identifier.parse(name);
            Biome biome = level.registryAccess()
                    .lookupOrThrow(Registries.BIOME)
                    .getValue(ResourceKey.create(Registries.BIOME, id));
            if (biome == null) return defaults();
            return new int[]{ biome.getGrassColor(0.0, 0.0), biome.getFoliageColor(), biome.getWaterColor() };
        } catch (Throwable t) {
            return defaults();
        }
    }

    private static int[] defaults() {
        return new int[]{ DEFAULT_GRASS, DEFAULT_FOLIAGE, DEFAULT_WATER };
    }
}
