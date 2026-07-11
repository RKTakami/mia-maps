package com.mia.aperture.map;

public final class BeaconGeometry {
    private BeaconGeometry() {}

    public record Screen(boolean onScreen, int x, int y, double dirX, double dirY, double depth) {}

    // Project a camera-relative world offset (relX/Y/Z) with the camera basis (forward,
    // up, left) and focal length onto a w x h screen. dirX/dirY give the screen-space
    // direction (y down) toward the point, for an edge arrow when it is off-screen.
    public static Screen project(double relX, double relY, double relZ,
            double fx, double fy, double fz, double ux, double uy, double uz,
            double lx, double ly, double lz, double focal, int w, int h) {
        double zc = relX * fx + relY * fy + relZ * fz;
        double xc = -(relX * lx + relY * ly + relZ * lz);
        double yc = relX * ux + relY * uy + relZ * uz;
        if (zc > 0.05) {
            int sx = (int) Math.round(w / 2.0 + (xc / zc) * focal);
            int sy = (int) Math.round(h / 2.0 - (yc / zc) * focal);
            boolean on = sx >= 0 && sx < w && sy >= 0 && sy < h;
            return new Screen(on, sx, sy, xc, -yc, zc);
        }
        // behind the camera: never on-screen; flip x so the edge arrow points correctly
        return new Screen(false, 0, 0, -xc, yc, zc);
    }

    // Clamp a screen-space direction to the screen edge (inset by margin).
    public static int[] edgeClamp(double dirX, double dirY, int w, int h, int margin) {
        if (dirX == 0 && dirY == 0) return new int[]{w / 2, h / 2};
        double ang = Math.atan2(dirY, dirX);
        int x = (int) Math.round(w / 2.0 + Math.cos(ang) * (w / 2.0 - margin));
        int y = (int) Math.round(h / 2.0 + Math.sin(ang) * (h / 2.0 - margin));
        x = Math.max(margin, Math.min(w - margin, x));
        y = Math.max(margin, Math.min(h - margin, y));
        return new int[]{x, y};
    }
}
