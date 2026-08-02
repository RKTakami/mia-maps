package com.mia.aperture.map;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;

/**
 * Single source of the Voxy {@link WorldEngine} the map reads from.
 *
 * <p>Historically every call site did {@code IGetVoxyRenderSystem.getNullable().getEngine()}, which
 * ties the map to Voxy's GL renderer. That renderer needs OpenGL 4.3 (compute) and 4.6
 * (indirect parameters); Apple capped OpenGL at 4.1, so on macOS the render system is never created
 * and the map had no data source even though the LOD store itself is perfectly readable.
 *
 * <p>Storage and ingest need no GL — Voxy builds the store from vanilla chunk hooks — so when the
 * render system is absent we ask the world for its engine directly. On a machine where Voxy renders,
 * the first branch is taken and behaviour is exactly as before.
 *
 * <p>Returns {@code null} when no engine is available; every caller already handled that, since a
 * null render system produced the same outcome.
 *
 * <p><b>The engine is replaced during play.</b> Voxy's active-world cleaner shuts the world down
 * when it looks idle and builds a fresh engine on the next access — observed roughly 25 times in one
 * evening's session. Everything derived from a section (map tiles, the orbit cloud, meshes, GPU
 * shells) belongs to the engine that produced it, so each replacement bumps {@link #generation()}
 * and every cache folds that into its signature. Without it those caches key on geometry alone: the
 * camera has not moved, so the signature matches, so the view keeps redrawing data from an engine
 * that no longer exists — which is precisely how the 3D view froze on old terrain.
 */
public final class MapEngineSource {
    private MapEngineSource() {}

    /** Identity of the last engine handed out. Compared by reference — a new engine is a new object. */
    private static volatile WorldEngine last;
    private static final java.util.concurrent.atomic.AtomicInteger GEN =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Bumped whenever the engine instance changes, including to and from null. Two threads racing
     * the same change may both bump it; that costs one extra rebuild and is not worth locking the
     * hot path to avoid.
     */
    public static int generation() { return GEN.get(); }

    public static WorldEngine get() {
        WorldEngine engine = resolve();
        if (engine != last) {
            last = engine;
            GEN.incrementAndGet();
        }
        return engine;
    }

    private static boolean voxyChecked = false;
    private static boolean voxyPresent = false;

    private static boolean isVoxyPresent() {
        if (!voxyChecked) {
            try {
                Class.forName("me.cortex.voxy.client.core.IGetVoxyRenderSystem");
                voxyPresent = true;
            } catch (Throwable t) {
                voxyPresent = false;
            }
            voxyChecked = true;
        }
        return voxyPresent;
    }

    private static WorldEngine resolve() {
        if (isVoxyPresent()) {
            try {
                WorldEngine engine = VoxyHelper.getEngine();
                if (engine != null) return engine;
            } catch (Throwable t) {
                // Ignore
            }
        }

        // No renderer (e.g. macOS, or Voxy rendering disabled). The engine still exists whenever
        // Voxy has an instance and a world; ofEngineNullable never creates one, so this cannot open
        // a store as a side effect of drawing a frame.
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        return WorldIdentifier.ofEngineNullable(mc.level);
    }

    private static class VoxyHelper {
        static WorldEngine getEngine() {
            VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
            return rs != null ? rs.getEngine() : null;
        }
    }
}
