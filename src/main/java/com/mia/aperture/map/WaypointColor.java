package com.mia.aperture.map;

public enum WaypointColor {
    RED(0xFFE04040),
    ORANGE(0xFFE09020),
    YELLOW(0xFFE0D040),
    GREEN(0xFF40C040),
    AQUA(0xFF40C0C0),
    BLUE(0xFF4060E0),
    PURPLE(0xFFA050E0),
    WHITE(0xFFF0F0F0);

    private final int argb;

    WaypointColor(int argb) { this.argb = argb; }

    public int argb() { return argb; }

    public WaypointColor next() {
        WaypointColor[] v = values();
        return v[(ordinal() + 1) % v.length];
    }
}
