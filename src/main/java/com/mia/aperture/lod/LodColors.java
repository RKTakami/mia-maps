package com.mia.aperture.lod;

import com.mia.aperture.map.BiomeTintResolver;
import com.mia.aperture.map.BlockColorBake;
import net.minecraft.client.Minecraft;

/**
 * The colour source for store-backed data, built once and shared.
 *
 * <p><b>Colour has to travel with the data.</b> Store cells carry ids this project interned —
 * canonical block-state strings resolved back through {@link LodBlockTable} — while Voxy cells carry
 * ids from its own mapper. The two number spaces are unrelated, so resolving one set through the
 * other's palette miscolours everything while still producing a plausible picture. Whichever source
 * a tile came from must supply its colours too.
 *
 * <p>Built once because baking every stored state needs the client's model shaper and costs roughly
 * a quarter of a second — fine at join, impossible per tile or per frame.
 */
public final class LodColors {
    private LodColors() {}

    private static volatile LodColorSource cached;
    private static volatile int bakedStates;

    /**
     * @return the shared source, or null when the store or the client world is not ready. Null means
     *         "ask again", not "unavailable" — the block table resolves at join and the level may
     *         not exist yet on the first call.
     */
    public static LodColorSource get() {
        long handle = LodIndexer.handle();
        if (handle == 0) return null;
        LodColorSource c = cached;
        LodBlockTable table = LodIndexer.blockTable();
        // Rebuild when the table has grown: indexing interns new states as the player explores, and
        // a stale bake would draw newly seen blocks with no colour at all.
        if (c != null && table.size() == bakedStates) return c;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return null;
        synchronized (LodColors.class) {
            if (cached != null && table.size() == bakedStates) return cached;
            long t0 = System.currentTimeMillis();
            BlockColorBake bake = new BlockColorBake();
            bake.update(table.size(), table::stateFor);
            BiomeTintResolver tints = new BiomeTintResolver(id -> {
                String key = LodNative.nBlockKey(handle, id);
                return key != null && key.startsWith(LodNative.BIOME_PREFIX)
                        ? key.substring(LodNative.BIOME_PREFIX.length()) : null;
            }, mc.level);
            bakedStates = table.size();
            cached = new LodColorSource(bake.snapshot(), tints);
            System.out.println("[MIA Maps] store colours baked: " + bakedStates + " states in "
                    + (System.currentTimeMillis() - t0) + "ms");
            return cached;
        }
    }

    /** Drop the bake on world change: interned ids mean different blocks in a different world. */
    public static void reset() {
        cached = null;
        bakedStates = 0;
    }
}
