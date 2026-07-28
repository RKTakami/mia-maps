package com.mia.aperture.lod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.Map;

/**
 * Extracts a canonical key from a live {@link BlockState}.
 *
 * <p>Deliberately thin: the formatting rules live in {@link BlockKey}, which is pure and tested
 * without Minecraft. This class only pulls the registry id and property values out of the game.
 */
public final class BlockKeys {
    private BlockKeys() {}

    /**
     * Canonical string for a block state, e.g.
     * {@code minecraft:oak_stairs[facing=north,half=bottom,waterlogged=false]}.
     *
     * <p><b>Every property is included</b>, which is correct but not free: states that look
     * identical — leaves differing only in {@code distance}, say — become separate ids and so
     * separate palette entries in a section. That is a known cost to <b>measure against real data</b>
     * rather than pre-empt with a hand-written list of "properties that matter", which would be
     * wrong for modded blocks and would drift. If palette sizes turn out to suffer, normalising is a
     * contained change here.
     */
    public static String of(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Map<Property<?>, Comparable<?>> values = state.getValues();
        if (values.isEmpty()) {
            return BlockKey.format(id, Map.of());
        }
        Map<String, String> props = new HashMap<>(values.size() * 2);
        for (Map.Entry<Property<?>, Comparable<?>> e : values.entrySet()) {
            props.put(e.getKey().getName(), name(e.getKey(), e.getValue()));
        }
        return BlockKey.format(id, props);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String name(Property<T> p, Comparable<?> value) {
        return p.getName((T) value);
    }
}
