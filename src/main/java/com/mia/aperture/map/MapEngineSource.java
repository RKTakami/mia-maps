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
 */
public final class MapEngineSource {
    private MapEngineSource() {}

    public static WorldEngine get() {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        if (rs != null) return rs.getEngine();

        // No renderer (e.g. macOS, or Voxy rendering disabled). The engine still exists whenever
        // Voxy has an instance and a world; ofEngineNullable never creates one, so this cannot open
        // a store as a side effect of drawing a frame.
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        return WorldIdentifier.ofEngineNullable(mc.level);
    }
}
