package com.mia.aperture.map;

public interface MapColorSource {
    int baseColor(long mappingId, Face face);
    boolean isWater(long mappingId);
    boolean isOpaque(long mappingId);
}
