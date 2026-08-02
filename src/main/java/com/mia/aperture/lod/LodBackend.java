package com.mia.aperture.lod;

import com.mia.loddy.api.LodService;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Manages the active distance LOD and map-store backend for MIA Mappy.
 * Allows runtime selection between standalone mia-loddy, Voxy, or vanilla/none.
 */
public enum LodBackend {
    LODDY("mia-loddy", "mia_loddy"),
    VOXY("Voxy", "voxy"),
    NONE("Off / Vanilla", null);

    private final String displayName;
    private final String modId;

    LodBackend(String displayName, String modId) {
        this.displayName = displayName;
        this.modId = modId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isInstalled() {
        if (modId == null) return true; // NONE is always available
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static boolean isLoddyInstalled() {
        return LODDY.isInstalled();
    }

    public static boolean isVoxyInstalled() {
        return VOXY.isInstalled();
    }

    public static LodBackend getActiveBackend(String settingValue) {
        if ("LODDY".equalsIgnoreCase(settingValue)) {
            if (isLoddyInstalled()) return LODDY;
        } else if ("VOXY".equalsIgnoreCase(settingValue)) {
            if (isVoxyInstalled()) return VOXY;
        } else if ("NONE".equalsIgnoreCase(settingValue)) {
            return NONE;
        }

        // Default auto-selection: prefer mia-loddy if installed, else Voxy, else none
        if (isLoddyInstalled()) return LODDY;
        if (isVoxyInstalled()) return VOXY;
        return NONE;
    }

    public static LodBackend nextAvailableBackend(LodBackend current) {
        LodBackend[] all = values();
        int idx = (current.ordinal() + 1) % all.length;
        for (int i = 0; i < all.length; i++) {
            LodBackend next = all[(idx + i) % all.length];
            if (next.isInstalled()) {
                return next;
            }
        }
        return NONE;
    }

    public static void applyBackend(LodBackend backend) {
        if (isLoddyInstalled()) {
            try {
                boolean enableLoddy = (backend == LODDY);
                LodService.getInstance().setWorldRenderingEnabled(enableLoddy);
                if (enableLoddy && LodService.getInstance().getStoreHandle() == 0 && LodIndexer.handle() != 0) {
                    LodService.getInstance().setStoreHandle(LodIndexer.handle());
                }
            } catch (Throwable t) {
                System.err.println("[MIA Mappy] Failed to apply mia-loddy world render state: " + t);
            }
        }
    }
}
