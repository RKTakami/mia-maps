package com.mia.aperture.client;

import me.cortex.voxy.client.config.VoxyConfig;

// Reads/writes Voxy's own `ingestEnabled` setting.
//
// Why we touch another mod's config at all: ingestion is what fills Voxy's LOD database, and that
// database IS our map's data source. With it off, MIA Maps shows nothing at all — and the MIA
// modpack ships it OFF by default, so a new user's first experience is a blank map with no clue
// why. Surfacing it here turns that into a one-click fix.
//
// Deliberately narrow: we only touch this one flag. Voxy's other settings (render distance in
// particular) affect the player's game rendering and performance, and are Voxy's business.
//
// Every call is defensive — Voxy is a compileOnly dependency and this reaches into its internals,
// so an API change must degrade to "unavailable", never crash the map.
public final class VoxyIngest {
    private VoxyIngest() {}

    // TRUE/FALSE = Voxy's current setting; null = couldn't read it (API changed / not loaded).
    public static Boolean enabled() {
        try {
            VoxyConfig c = VoxyConfig.CONFIG;
            return c == null ? null : c.ingestEnabled;
        } catch (Throwable t) {
            return null;
        }
    }

    // Returns true if the change was applied and persisted to Voxy's config.
    public static boolean setEnabled(boolean value) {
        try {
            VoxyConfig c = VoxyConfig.CONFIG;
            if (c == null) return false;
            c.ingestEnabled = value;
            c.save();
            return true;
        } catch (Throwable t) {
            System.err.println("[MIA Maps] could not change Voxy ingestion: " + t);
            return false;
        }
    }
}
