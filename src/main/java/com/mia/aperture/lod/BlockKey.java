package com.mia.aperture.lod;

import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical string for a block state — the anchor that makes stored ids stable.
 *
 * <p>The store maps these strings to ids and persists both. The game's own runtime ids cannot be
 * used for that: they shift with the Minecraft version and the installed mod set, so terrain indexed
 * under one setup would be silently reinterpreted under another. A string like
 * {@code minecraft:oak_stairs[facing=north,half=bottom]} does not move.
 *
 * <p>The formatting is deliberately separated from Minecraft so it can be tested without the game —
 * {@link #format} is pure, and {@link com.mia.aperture.lod.BlockKeys} does the extraction.
 */
public final class BlockKey {
    private BlockKey() {}

    /**
     * Build the canonical string.
     *
     * <p><b>Properties are sorted by name.</b> Minecraft's property iteration order is not something
     * to depend on: if it ever differed between runs or versions, the same block would produce two
     * keys, quietly splitting one block into two ids and doubling its share of the table.
     *
     * @param blockId  namespaced block id, e.g. {@code minecraft:stone}
     * @param props    property name → value; may be empty
     */
    public static String format(String blockId, Map<String, String> props) {
        if (blockId == null || blockId.isEmpty()) {
            throw new IllegalArgumentException("blockId must not be empty");
        }
        if (props == null || props.isEmpty()) {
            return blockId;
        }
        // TreeMap rather than sorting a list: it also collapses a duplicate name to one entry, which
        // a malformed caller could otherwise use to produce two keys for one state.
        TreeMap<String, String> sorted = new TreeMap<>(props);
        StringBuilder sb = new StringBuilder(blockId.length() + sorted.size() * 12);
        sb.append(blockId).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
