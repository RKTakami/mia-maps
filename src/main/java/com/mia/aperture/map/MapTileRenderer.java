package com.mia.aperture.map;

public final class MapTileRenderer {
    private static final int CELLS = 32;
    private static final float RELIEF_SLOPE_K = 0.04f;
    private static final float RELIEF_MIN = 0.55f;
    private static final float RELIEF_MAX = 1.35f;
    private static final int VANILLA_HIGH = 255;
    private static final int VANILLA_NORMAL = 220;
    private static final int VANILLA_LOW = 180;
    private static final int WATER_FLOOR_SCAN_CELLS = 32;
    private static final float SATURATION = 1.15f;
    private static final float CONTRAST = 1.04f;
    private static final int CAVE_DEPTH_RANGE = 48;
    private static final float CAVE_MIN_BRIGHT = 0.28f;
    private static final float CAVE_MAX_BRIGHT = 1.22f;
    private static final float CAVE_RELIEF_K = 0.06f;
    private static final float CAVE_RELIEF_MIN = 0.72f;
    private static final float CAVE_RELIEF_MAX = 1.28f;

    private MapTileRenderer() {}

    // sections: top-to-bottom stack covering the band; entries may be null (missing).
    // topSectionTopY: shifted block Y of the TOP face of sections[0].
    // stackBaseY: shifted block Y of the BOTTOM face of the LAST section
    //   (= topSectionTopY - sections.length * CELLS * cellSize).
    public static void renderTile(long[][] sections, int topSectionTopY, int bandTopY,
                                  int stackBaseY, int cellSize, MapMode mode,
                                  MapColorSource colors, int[] outColor, int[] outHeight) {
        long[] surfaceId = new long[CELLS * CELLS];
        int totalCellsY = sections.length * CELLS;
        // Cave mode skips the solid overburden: it descends until it enters an air void,
        // then draws the first solid below (the cave floor). Columns that never open into
        // air stay transparent (black), which is what reveals the tunnel network.
        boolean caveScan = mode == MapMode.CAVE;

        for (int z = 0; z < CELLS; z++) {
            for (int x = 0; x < CELLS; x++) {
                int out = z * CELLS + x;
                outColor[out] = 0;
                outHeight[out] = Integer.MIN_VALUE;
                surfaceId[out] = 0;

                int startCell = Math.min(totalCellsY - 1,
                        Math.floorDiv(bandTopY - stackBaseY, cellSize));
                boolean sawAir = false;
                for (int cy = startCell; cy >= 0; cy--) {
                    long id = cellAt(sections, cy, x, z, totalCellsY);
                    boolean opaque = id != 0 && colors.isOpaque(id);
                    if (!opaque) {
                        sawAir = true;
                        continue;
                    }
                    if (caveScan && !sawAir) continue;
                    surfaceId[out] = id;
                    outHeight[out] = stackBaseY + cy * cellSize;
                    break;
                }
            }
        }

        for (int z = 0; z < CELLS; z++) {
            for (int x = 0; x < CELLS; x++) {
                int out = z * CELLS + x;
                long id = surfaceId[out];
                if (id == 0) continue;
                int h = outHeight[out];

                int base;
                if (colors.isWater(id)) {
                    base = waterColor(sections, colors, x, z, h, stackBaseY, cellSize,
                            colors.baseColor(id, Face.TOP), totalCellsY);
                } else {
                    base = ColorMath.punch(colors.baseColor(id, Face.TOP), SATURATION, CONTRAST);
                }

                int hNorth = z > 0 ? outHeight[out - CELLS] : h;
                if (hNorth == Integer.MIN_VALUE) hNorth = h;

                if (mode == MapMode.CAVE) {
                    double t = Math.max(0.0, Math.min(1.0,
                            (h - (bandTopY - CAVE_DEPTH_RANGE)) / (double) CAVE_DEPTH_RANGE));
                    float depth = CAVE_MIN_BRIGHT + (CAVE_MAX_BRIGHT - CAVE_MIN_BRIGHT) * (float) t;
                    float relief = 1.0f + CAVE_RELIEF_K * (h - hNorth);
                    relief = Math.max(CAVE_RELIEF_MIN, Math.min(CAVE_RELIEF_MAX, relief));
                    outColor[out] = scale(base, depth * relief);
                } else if (mode == MapMode.VANILLA) {
                    int mult = h > hNorth ? VANILLA_HIGH : h < hNorth ? VANILLA_LOW : VANILLA_NORMAL;
                    outColor[out] = scale(base, mult / 255.0f);
                } else {
                    float b = 1.0f + RELIEF_SLOPE_K * (h - hNorth);
                    b = Math.max(RELIEF_MIN, Math.min(RELIEF_MAX, b));
                    outColor[out] = scale(base, b);
                }
            }
        }
    }

    private static long cellAt(long[][] sections, int cellY, int x, int z, int totalCellsY) {
        int fromTop = totalCellsY - 1 - cellY;
        int sectionIdx = fromTop / CELLS;
        long[] section = sections[sectionIdx];
        if (section == null) return 0;
        int localY = cellY % CELLS;
        return section[(localY << 10) | (z << 5) | x];
    }

    private static int waterColor(long[][] sections, MapColorSource colors, int x, int z,
                                  int surfaceY, int stackBaseY, int cellSize,
                                  int waterBase, int totalCellsY) {
        int surfaceCell = Math.floorDiv(surfaceY - stackBaseY, cellSize);
        int depthCells = WATER_FLOOR_SCAN_CELLS;
        int floorColor = 0;
        for (int d = 1; d <= WATER_FLOOR_SCAN_CELLS; d++) {
            int cy = surfaceCell - d;
            if (cy < 0) break;
            long id = cellAt(sections, cy, x, z, totalCellsY);
            if (id == 0) continue;
            if (!colors.isWater(id) && colors.isOpaque(id)) {
                depthCells = d;
                floorColor = colors.baseColor(id, Face.TOP);
                break;
            }
        }
        float darken = Math.max(0.4f, 1.0f - 0.05f * depthCells);
        if (floorColor == 0) return scale(waterBase, darken);
        return scale(blend(floorColor, waterBase, 0.6f), darken);
    }

    private static int scale(int argb, float f) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((argb & 0xFF) * f));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
