package com.mia.aperture.map;

public final class MapSettings {
    public enum Orientation { NORTH_UP, HEADING_UP }
    public enum FrameShape { SQUARE, ROUND }
    public enum MinimapCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    public enum CaveMode { AUTO, ON, OFF }

    public static final int MIN_SIZE = 80;
    public static final int MAX_SIZE = 256;

    public Orientation orientation = Orientation.NORTH_UP;
    public FrameShape shape = FrameShape.SQUARE;
    public int minimapSize = 100;
    public double minimapX = 1.0;
    public double minimapY = 0.0;
    public CaveMode caveMode = CaveMode.AUTO;
    public boolean showBeacons = true;

    public void setMinimapSize(int px) {
        this.minimapSize = Math.max(MIN_SIZE, Math.min(MAX_SIZE, px));
    }

    public void setMinimapPos(double fx, double fy) {
        this.minimapX = Math.max(0.0, Math.min(1.0, fx));
        this.minimapY = Math.max(0.0, Math.min(1.0, fy));
    }
}
