package com.mia.aperture.map;

public enum MapMode {
    RELIEF,
    VANILLA,
    /**
     * Depth slice: paints only cave floors near the band top, shaded by how far below it they sit.
     *
     * <p><b>This is not the X-ray removed in {@code dfeb3e5}, and must not become it.</b> That
     * feature let you see through terrain from the surface, which needs the server admin's written
     * permission to publish. This one draws the layer you are <i>already standing in</i>: it is
     * bounded to {@code CAVE_SLICE_BLOCKS} below the band top and may only tunnel
     * {@code CAVE_PENETRATION_BLOCKS} through unbroken rock — both enforced in
     * {@link MapTileRenderer} and both covered by tests. Stood on the surface it is
     * indistinguishable from {@link #RELIEF}.
     */
    CAVES
}
