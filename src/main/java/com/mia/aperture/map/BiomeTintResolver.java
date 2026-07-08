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

    private final Mapper mapper;
    private final Level level;
    // biomeId -> {grass, foliage, water}
    private final ConcurrentHashMap<Integer, int[]> cache = new ConcurrentHashMap<>();

    public BiomeTintResolver(Mapper mapper, Level level) {
        this.mapper = mapper;
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
            Mapper.BiomeEntry[] entries = mapper.getBiomeEntries();
            if (biomeId < 0 || biomeId >= entries.length) return defaults();
            Identifier id = Identifier.parse(entries[biomeId].biome);
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
