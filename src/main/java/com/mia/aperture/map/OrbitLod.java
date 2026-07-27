package com.mia.aperture.map;

// How the 3D view's detail level and true coverage fall out of the 3D Area and 3D Quality settings.
// Both the renderer and the settings screen read this, so the numbers shown to the user are the same
// ones the renderer acts on. The relationship is not obvious: the camera samples 3x the requested
// area for the frustum footprint, the level climbs until that fits the quality tier's grid budget,
// and the horizontal extent is then clamped to what the budget can hold — so a large area at a low
// tier silently covers LESS ground than asked for. Grid cost is cubic, so that clamp is what keeps
// wide views renderable at all.
public final class OrbitLod {

    // level: LOD level chosen. cellBlocks: voxel edge in blocks (1 << level). coverageBlocks: the
    // horizontal extent actually sampled. clamped: coverage fell short of the 3x request.
    public record Plan(int level, int cellBlocks, int coverageBlocks, boolean clamped) {}

    // Voxy stores nothing coarser than level 4 (WorldEngine.MAX_LOD_LAYER), so the search stops there.
    public static final int MAX_LEVEL = 4;

    private OrbitLod() {}

    // Zoom quantised into 64-block buckets so scrolling reuses the same mesh across nearby zooms.
    public static int baseFor(int extentXZ) {
        return Math.max(64, ((extentXZ + 63) / 64) * 64);
    }

    // vertUp/vertDown are the sampled band above and below the focus, already clamped to the Abyss.
    public static Plan plan(int extentXZ, int vertUp, int vertDown, int gpuGrid, int maxLevel) {
        int base = baseFor(extentXZ);
        int wanted = base * 3;
        int maxExtent = Math.max(wanted, vertUp + vertDown);
        int level = 0;
        while ((maxExtent >> level) > gpuGrid && level < maxLevel) level++;
        int coverage = Math.min(wanted, gpuGrid << level);
        return new Plan(level, 1 << level, coverage, coverage < wanted);
    }

    // The nominal plan for an area setting, with the vertical band unclamped (VERT_UP/VERT_DOWN are
    // 1.5x each, so vertical matches the 3x horizontal). This is what the settings screen reports —
    // near the Abyss rim the real vertical band is shorter, which can only give a FINER level.
    public static Plan planForArea(int areaBlocks, int gpuGrid, int maxLevel) {
        int base = baseFor(areaBlocks);
        int half = (base * 3) / 2;
        return plan(areaBlocks, half, half, gpuGrid, maxLevel);
    }
}
