package com.mia.aperture.map;

public final class CaveDetector {
    public static final int DEBOUNCE_TICKS = 8;

    private boolean stable = false;
    private int count = 0;

    // Flip the stable state only after DEBOUNCE_TICKS consecutive ticks of the new
    // value; any tick matching the current stable value resets the counter.
    public boolean debounce(boolean raw) {
        if (raw == stable) {
            count = 0;
            return stable;
        }
        count++;
        if (count >= DEBOUNCE_TICKS) {
            stable = raw;
            count = 0;
        }
        return stable;
    }

    public boolean isStable() {
        return stable;
    }

    public static boolean caveActive(MapSettings.CaveMode mode, boolean enclosed) {
        return switch (mode) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> enclosed;
        };
    }
}
