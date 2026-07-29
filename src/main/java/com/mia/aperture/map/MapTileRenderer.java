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

    // --- MapMode.CAVES ------------------------------------------------------------------------
    // Bounds and shading live in CaveShading, shared with the 3D view so the two cannot drift.
    static final int CAVE_SLICE_BLOCKS = CaveShading.SLICE_BLOCKS;
    static final int CAVE_PENETRATION_BLOCKS = CaveShading.PENETRATION_BLOCKS;

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

        for (int z = 0; z < CELLS; z++) {
            for (int x = 0; x < CELLS; x++) {
                int out = z * CELLS + x;
                outColor[out] = 0;
                outHeight[out] = Integer.MIN_VALUE;
                surfaceId[out] = 0;

                int startCell = Math.min(totalCellsY - 1,
                        Math.floorDiv(bandTopY - stackBaseY, cellSize));

                // CAVES scans a bounded slice for floors; the other modes scan the whole band for
                // the first opaque cell, which underground is the rock you are embedded in.
                boolean caves = mode == MapMode.CAVES;
                int stopCell = caves
                        ? Math.max(0, startCell - Math.max(1, CAVE_SLICE_BLOCKS / cellSize))
                        : 0;
                int rockBudget = Math.max(1, CAVE_PENETRATION_BLOCKS / cellSize);
                int rock = 0;

                for (int cy = startCell; cy >= stopCell; cy--) {
                    long id = cellAt(sections, cy, x, z, totalCellsY);
                    boolean opaque = id != 0 && colors.isOpaque(id);
                    if (!opaque) {
                        rock = 0;
                        continue;
                    }
                    if (caves) {
                        // A floor is opaque with open space directly above it. Rock with rock above
                        // is not a surface you could stand on, so keep descending — but only a
                        // little way, or the slice would see through the wall into the next cave.
                        boolean openAbove;
                        if (cy + 1 >= totalCellsY) {
                            // Nothing above the stack to check. Assume rock: the slice must never
                            // paint a cell it cannot prove is open, and this is exactly the case
                            // where a band top landing on a section boundary would otherwise wash
                            // the whole tile in solid stone.
                            openAbove = false;
                        } else {
                            long a = cellAt(sections, cy + 1, x, z, totalCellsY);
                            openAbove = a == 0 || !colors.isOpaque(a);
                        }
                        if (!openAbove) {
                            if (++rock >= rockBudget) break;
                            continue;
                        }
                    }
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

                if (mode == MapMode.CAVES) {
                    // Depth from the band top, not the neighbour's height: in a cave the useful
                    // question is "how far below me is that floor", not "which way does it slope".
                    // Two passages at different heights separate immediately.
                    outColor[out] = CaveShading.shade(base, bandTopY - h);
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
