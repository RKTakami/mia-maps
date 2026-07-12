package com.mia.aperture.map;

// A bounded box of Voxy opaque data (index layout matches VoxelCloud: (y*gz+z)*gx+x).
// A cell is "standable" if it has solid ground below and 2 air cells (feet+head) of headroom.
public final class TraversabilityGrid {
    private final boolean[] opaque;
    public final int gx, gy, gz;

    public TraversabilityGrid(boolean[] opaque, int gx, int gy, int gz) {
        this.opaque = opaque;
        this.gx = gx; this.gy = gy; this.gz = gz;
    }

    // Out-of-bounds is treated as air (open); below-floor OOB just means "no ground".
    public boolean opaque(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= gx || y >= gy || z >= gz) return false;
        return opaque[(y * gz + z) * gx + x];
    }

    public boolean standable(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= gx || z >= gz || y < 1 || y >= gy) return false;
        return opaque(x, y - 1, z) && !opaque(x, y, z) && !opaque(x, y + 1, z);
    }
}
