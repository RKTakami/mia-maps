package com.mia.aperture.lod;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockKeyTest {

    @Test
    void aBlockWithNoPropertiesIsJustItsId() {
        assertEquals("minecraft:stone", BlockKey.format("minecraft:stone", Map.of()));
        assertEquals("minecraft:stone", BlockKey.format("minecraft:stone", null));
    }

    @Test
    void propertiesAreSortedByName() {
        // Minecraft's property iteration order is not something to depend on. If it ever differed
        // between runs or versions, one block would produce two keys — splitting it into two ids,
        // doubling its share of the table, and fragmenting section palettes.
        Map<String, String> a = new LinkedHashMap<>();
        a.put("half", "bottom");
        a.put("facing", "north");
        a.put("waterlogged", "false");

        Map<String, String> b = new LinkedHashMap<>();
        b.put("waterlogged", "false");
        b.put("facing", "north");
        b.put("half", "bottom");

        assertEquals(BlockKey.format("minecraft:oak_stairs", a),
                     BlockKey.format("minecraft:oak_stairs", b),
                     "insertion order must not change the key");
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom,waterlogged=false]",
                     BlockKey.format("minecraft:oak_stairs", a));
    }

    @Test
    void differentStatesOfOneBlockGetDifferentKeys() {
        String north = BlockKey.format("minecraft:oak_stairs", Map.of("facing", "north"));
        String south = BlockKey.format("minecraft:oak_stairs", Map.of("facing", "south"));
        assertNotEquals(north, south);
    }

    @Test
    void differentBlocksWithTheSamePropertiesGetDifferentKeys() {
        Map<String, String> p = Map.of("facing", "north");
        assertNotEquals(BlockKey.format("minecraft:oak_stairs", p),
                        BlockKey.format("minecraft:stone_stairs", p));
    }

    @Test
    void aSinglePropertyNeedsNoSeparator() {
        assertEquals("minecraft:furnace[lit=true]",
                     BlockKey.format("minecraft:furnace", Map.of("lit", "true")));
    }

    @Test
    void modNamespacesSurviveIntact() {
        // Third-party blocks are the reason the key is namespaced at all.
        assertEquals("someothermod:machine[active=true]",
                     BlockKey.format("someothermod:machine", Map.of("active", "true")));
    }

    @Test
    void anEmptyBlockIdIsRejected() {
        // An empty key would be interned as a real block and collide with anything else empty,
        // mislabelling terrain rather than failing visibly.
        assertThrows(IllegalArgumentException.class, () -> BlockKey.format("", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> BlockKey.format(null, Map.of()));
    }

    @Test
    void theKeyIsStableAcrossRepeatedCalls() {
        // Ids are anchored to this string and persisted, so the same state must always produce the
        // same bytes — this is the property the whole store depends on.
        Map<String, String> p = Map.of("facing", "east", "half", "top");
        String first = BlockKey.format("minecraft:oak_stairs", p);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, BlockKey.format("minecraft:oak_stairs", p));
        }
    }
}
