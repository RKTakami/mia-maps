package com.mia.aperture.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
    // maxCells bounds the grid's VOLUME. Width alone is not a cost bound — the grid is gX*gY*gZ cells
    // and every one is sampled, so capping only the axis let a wide view reach 191M cells (911 MB,
    // 1.23 s per rebuild). Coarsen until the volume fits; at the level ceiling, give up horizontal
    // coverage rather than the budget.
    public static Plan plan(int extentXZ, int vertUp, int vertDown, int gpuGrid, int maxLevel, long maxCells) {
        int base = baseFor(extentXZ);
        int wanted = base * 3;
        int vert = Math.max(1, vertUp + vertDown);
        // Start at the FINEST level and coarsen only until the volume fits, so the budget buys detail.
        // A per-axis width cap must not gate this: consulting it first forced the level coarse before
        // the budget was ever consulted, which made wide views both chunky AND narrow. gpuGrid now
        // only bounds width as a safety rail — volume is the real constraint.
        int level = 0;
        while (level < maxLevel && cellsAt(wanted, vert, level) > maxCells) level++;

        int cell = 1 << level;
        int coverage = Math.min(wanted, gpuGrid << level);
        // Still over budget at the ceiling (a tall Abyss band does this): shrink the horizontal span
        // until gX*gY*gX fits. gX = sqrt(maxCells / gY).
        long gY = Math.max(1, vert / cell);
        long widest = Math.max(1, (long) Math.sqrt((double) maxCells / gY));
        coverage = Math.max(cell, (int) Math.min(coverage, widest * cell));
        return new Plan(level, cell, coverage, coverage < wanted);
    }

    // Volume at the FULL requested span. Measuring against a width-capped span would let the level
    // look affordable only because the view had already been narrowed, which is how a 4096 request
    // ended up mapping ~2112 blocks.
    private static long cellsAt(int wanted, int vert, int level) {
        int cell = 1 << level;
        long gX = Math.max(1, wanted / cell);
        long gY = Math.max(1, vert / cell);
        return gX * gY * gX;
    }

    // The nominal plan for an area setting, with the vertical band unclamped (VERT_UP/VERT_DOWN are
    // 1.5x each, so vertical matches the 3x horizontal). This is what the settings screen reports —
    // near the Abyss rim the real vertical band is shorter, which can only afford MORE coverage.
    public static Plan planForArea(int areaBlocks, int gpuGrid, int maxLevel, long maxCells) {
        int base = baseFor(areaBlocks);
        int half = (base * 3) / 2;
        return plan(areaBlocks, half, half, gpuGrid, maxLevel, maxCells);
    }

    // ---------------------------------------------------------------------------------------
    // Cascaded LOD (see docs/plans/specs/2026-07-27-cascaded-lod-3d-view-design.md)
    //
    // plan() above picks ONE level for the whole box, so cost is cubic in (span / cell) and fine
    // voxels over a wide area are unreachable at any budget. Concentric shells make the cost
    // ADDITIVE instead: a small fine box around the focus, wrapped in progressively coarser and
    // wider ones. Each shell keeps the same cell COUNT per axis, so each costs about the same, and
    // total cost is ~shells x that — which is why this buys detail the single-level planner cannot.
    // ---------------------------------------------------------------------------------------

    // One concentric box. spanBlocks is the full horizontal edge, vertBlocks the full vertical edge
    // (an inner shell is a box around the focus, so it is not taller than it is wide). Shell k+1 is
    // one level coarser and twice as wide as shell k.
    public record Shell(int level, int cellBlocks, int spanBlocks, int vertBlocks) {
        // Matches VoxelCloud.sampleGrid's arithmetic: gX * gY * gZ with gZ == gX.
        public long cells() {
            long gX = Math.max(1, spanBlocks / cellBlocks);
            long gY = Math.max(1, vertBlocks / cellBlocks);
            return gX * gY * gX;
        }
    }

    // Innermost shell FIRST. The last shell always spans the full requested footprint, so coverage
    // is never silently reduced the way the single-level planner has to do.
    //
    // No gpuGrid rail here: each shell's width in cells is wanted >> outerLevel by construction, so
    // width cannot run away on its own — volume is the only real constraint, which is the lesson
    // plan() already encodes.
    //
    // maxLevel above 4 requires synthesis (Voxy stores nothing coarser than WorldEngine.MAX_LOD_LAYER),
    // and that synthesis MUST use LodUpsampler.mipInto's drawable-child predicate or surfaces fill
    // with holes — see the pin-art regression fixed in 9b519db.
    public static List<Shell> planCascade(int extentXZ, int vertUp, int vertDown,
                                          int maxLevel, long maxCells, int maxShells) {
        int base = baseFor(extentXZ);
        int wanted = base * 3;
        int vert = Math.max(1, vertUp + vertDown);

        List<Shell> best = null;
        // A COARSER outermost level is counter-intuitively better for detail: the outer shell gets
        // cheap, which frees budget for more (and finer) inner shells. Distant terrain being blocky
        // is exactly the trade a cascade is meant to make.
        for (int outer = 0; outer <= maxLevel; outer++) {
            List<Shell> shells = new ArrayList<>();
            long total = 0;
            for (int k = 0; k < maxShells; k++) {
                int level = outer - k;
                if (level < 0) break;
                int span = Math.max(1 << level, wanted >> k);
                Shell s = new Shell(level, 1 << level, span, Math.min(vert, span));
                if (total + s.cells() > maxCells) break;
                total += s.cells();
                shells.add(s);
            }
            if (shells.isEmpty()) continue;               // even the outer shell blew the budget
            if (best == null || isBetter(shells, best)) best = shells;
        }

        if (best == null) {
            // Nothing fits even at the coarsest level — fall back to the single-level planner, which
            // is allowed to give up coverage, so the view degrades to today's behaviour instead of
            // failing. The rail is `wanted` rather than a sentinel: plan() computes gpuGrid << level,
            // so Integer.MAX_VALUE there OVERFLOWS to a negative bound and collapses coverage to a
            // single cell. `wanted` is large enough never to bind and small enough never to overflow.
            Plan p = plan(extentXZ, vertUp, vertDown, wanted, maxLevel, maxCells);
            return List.of(new Shell(p.level(), p.cellBlocks(), p.coverageBlocks(),
                    Math.min(vert, p.coverageBlocks())));
        }

        List<Shell> out = new ArrayList<>(best);
        Collections.reverse(out);                          // innermost first
        return List.copyOf(out);
    }

    // Invalidation key for one sampled grid — the ONLY thing whose change actually produces a
    // different grid.
    //
    // VoxelCloud.sampleGrid snaps its origin to the cell lattice (originCellX = floorDiv(focusX,
    // cell) - gX/2), so the sampled region only moves in whole-cell steps. Keying the cache on the
    // RAW focus therefore rebuilds an identical grid for every block of movement: at level 4 that is
    // 15 wasted rebuilds out of every 16. Keying on the SNAPPED origin rebuilds only when the grid
    // genuinely shifts.
    //
    // This matters most for panning, which is a first-class interaction (right-click moves the focus,
    // R recentres) and for cascades, where each shell must invalidate on its OWN cell size so a drag
    // rebuilds the small inner shell rather than the wide expensive ones.
    //
    // NOTE: this deliberately does NOT capture world-data changes. While ingest is running the store
    // grows under a cached grid, so a caller that needs freshness must mix in its own generation or
    // time term — the previous per-block key refreshed constantly by accident, and that accident is
    // what this removes.
    public static long gridSig(int focusX, int focusY, int focusZ,
                               int extentXZ, int extentUp, int extentDown, int level) {
        int cell = 1 << level;
        return Objects.hash(
                Math.floorDiv(focusX, cell), Math.floorDiv(focusY, cell), Math.floorDiv(focusZ, cell),
                Math.max(1, extentXZ / cell), Math.max(0, extentUp / cell), Math.max(0, extentDown / cell),
                level);
    }

    // Per-shell key, so shells invalidate independently: the 32-block shell shifts once per 32 blocks
    // of pan, the 4-block shell once per 4. A single key over the whole cascade would rebuild every
    // shell whenever any one of them moved, which is exactly the cost cascades exist to avoid.
    public static long shellSig(Shell s, int focusX, int focusY, int focusZ) {
        int half = s.vertBlocks() / 2;
        return gridSig(focusX, focusY, focusZ, s.spanBlocks(), half, half, s.level());
    }

    // Finest innermost shell wins; ties go to fewer shells, since every extra shell is another mesh,
    // another draw and another seam to hide.
    private static boolean isBetter(List<Shell> a, List<Shell> b) {
        int ia = a.get(a.size() - 1).level(), ib = b.get(b.size() - 1).level();
        if (ia != ib) return ia < ib;
        return a.size() < b.size();
    }
}
