package com.mia.aperture.map;

public final class Waypoint {
    public String name;
    public int x;
    public int y;
    public int z;
    public WaypointColor color;
    public boolean visible = true;

    public Waypoint() {}

    public Waypoint(String name, int x, int y, int z, WaypointColor color) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
    }
}
