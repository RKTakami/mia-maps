package com.mia.aperture.lod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves stored block ids back to live {@link BlockState}s.
 *
 * <p>This is the link the whole read path rests on. Ids in the store are anchored to a block state's
 * canonical string precisely so that they survive a version or mod-set change — but that only pays
 * off if we can walk the string back to a state in the <i>current</i> game.
 *
 * <p>Resolution goes through the registry rather than a string parser: every state the game knows is
 * enumerated, keyed by the same {@link BlockKeys#of} used when indexing, and matched. That way the
 * two directions cannot disagree about formatting — if they ever did, blocks would silently resolve
 * to nothing and terrain would read as empty.
 *
 * <p>Ids that do not resolve are expected and survivable: a block from a mod that has since been
 * removed leaves terrain indexed under a key nothing answers to. Those read as air rather than as
 * the magenta missing-texture, which would look like real terrain.
 */
public final class LodBlockTable {
    private BlockState[] byId = new BlockState[0];
    private int resolved;
    private int unresolved;

    /** How many ids the current game could resolve. */
    public int resolvedCount() { return resolved; }

    /** How many could not — terrain indexed under blocks this game no longer has. */
    public int unresolvedCount() { return unresolved; }

    /** Highest id + 1, i.e. the size the colour bake needs. */
    public int size() { return byId.length; }

    /** The state for an id, or null if it did not resolve. */
    public BlockState stateFor(int id) {
        return id >= 0 && id < byId.length ? byId[id] : null;
    }

    /**
     * Build the mapping for a store.
     *
     * <p>Enumerates every block state the game knows — a few tens of thousands, once per session —
     * and matches by canonical key. Cheaper than it sounds, and immune to the formatting drift a
     * hand-written parser would invite.
     */
    public synchronized void resolve(long handle) {
        Map<String, BlockState> byKey = new HashMap<>(32768);
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                byKey.put(BlockKeys.of(state), state);
            }
        }

        // Ids are assigned densely from 1, so walk until the table clearly ends rather than
        // guessing an upper bound and making tens of thousands of pointless JNI calls.
        java.util.List<String> keys = new java.util.ArrayList<>();
        int consecutiveMisses = 0;
        for (int id = 1; consecutiveMisses < 64; id++) {   // id 0 is air, never interned
            String key = LodNative.nBlockKey(handle, id);
            keys.add(key);
            consecutiveMisses = key == null ? consecutiveMisses + 1 : 0;
        }

        BlockState[] table = new BlockState[keys.size() + 1];
        int ok = 0, missing = 0;
        for (int id = 1; id <= keys.size(); id++) {
            String key = keys.get(id - 1);
            if (key == null) continue;          // a gap, not a failure
            BlockState state = byKey.get(key);
            if (state != null) {
                table[id] = state;
                ok++;
            } else {
                missing++;
                if (missing <= 5) {
                    System.out.println("[MIA Maps] LOD block unresolved: " + key);
                }
            }
        }
        byId = table;
        resolved = ok;
        unresolved = missing;
    }
}
