package com.mia.aperture.map;

import com.mia.aperture.map.MapSettings.Orientation;

public final class MinimapMarkers {
    private MinimapMarkers() {}

    // North's on-screen angle, clockwise from up, in degrees.
    private static double northAngleDeg(Orientation o, float yaw) {
        return o == Orientation.NORTH_UP ? 0.0 : -(yaw + 180.0);
    }

    // Screen position of a cardinal (0=N,1=E,2=S,3=W) on a circle radius r about (cx,cy).
    public static int[] cardinalPos(int cx, int cy, int r, Orientation o, float yaw, int cardinal) {
        double a = Math.toRadians(northAngleDeg(o, yaw) + cardinal * 90.0);
        int x = cx + (int) Math.round(r * Math.sin(a));
        int y = cy - (int) Math.round(r * Math.cos(a));
        return new int[]{x, y};
    }

    // Rotation (radians) to apply to the minimap texture via pose().rotate.
    public static float headingRotationRad(Orientation o, float yaw) {
        return o == Orientation.NORTH_UP ? 0f : (float) Math.toRadians(-(yaw + 180.0));
    }
}
