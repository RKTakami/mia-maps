package com.mia.aperture.map;

public interface MapColorSource {
    int baseColor(long mappingId);
    boolean isWater(long mappingId);
    boolean isOpaque(long mappingId);
}
