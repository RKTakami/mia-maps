package com.mia.aperture.map;

public final class MapSettings {
    public enum Orientation { NORTH_UP, HEADING_UP }
    public enum FrameShape { SQUARE, ROUND }
    public enum MinimapCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /**
     * How heavy the minimap's frame is.
     *
     * <p>SOLID is cast brass wide enough to carry the cardinal studs. WIRE is a thin bent-wire rim,
     * for players who would rather see terrain than furniture — at a small minimap size the solid
     * bezel is a real fraction of the widget.
     */
    public enum BezelStyle {
        SOLID("Solid brass", 7), WIRE("Thin wire", 2);

        public final String label;
        /** Frame width in pixels. The whole difference between the two styles derives from this. */
        public final int width;

        BezelStyle(String label, int width) { this.label = label; this.width = width; }

        public BezelStyle next() {
            BezelStyle[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    // 3D Orbit View quality tiers: texture resolution, point budget, and max splat radius.
    // Higher tiers look sharper but cost more per frame + memory; lower tiers keep weak PCs usable.
    public enum OrbitQuality {
        // textureSize drives GPU upload cost (size^2); maxPoints drives CPU-path detail (off-thread,
        // cheap). gpuGrid caps the grid's WIDTH in cells. From Potato boxes to Ultra machines.
        //
        // maxCells caps the grid's VOLUME, and is the setting that actually governs cost: a grid is
        // gX*gY*gZ cells at 5 bytes each, sampled cell by cell, rebuilt on every pan/zoom. Capping
        // width alone let Ultra reach 576^3 = 191M cells — 911 MB and 1.23 SECONDS per rebuild.
        // Measured fill cost is ~4 ms per million cells, and the rebuild runs on the WORKER thread,
        // so it delays the map catching up rather than stalling a frame — the earlier stutter came
        // from GC churn on hundreds of MB, not from the sampling time itself. Budgets are set so a
        // rebuild stays well under a second and transient memory stays double-digit MB.
        // gpuGrid stays only as a width safety rail; raised so the volume budget binds first.
        POTATO("Potato", 768, 20000, 10, 192, 4_000_000L),
        LOW("Low", 1024, 50000, 16, 256, 9_000_000L),
        MEDIUM("Medium", 2048, 150000, 30, 384, 18_000_000L),
        HIGH("High", 3072, 320000, 56, 512, 28_000_000L),
        ULTRA("Ultra", 4096, 600000, 88, 640, 40_000_000L);

        public final String label;
        public final int textureSize, maxPoints, maxRadius, gpuGrid;
        public final long maxCells;

        OrbitQuality(String label, int textureSize, int maxPoints, int maxRadius, int gpuGrid, long maxCells) {
            this.label = label;
            this.textureSize = textureSize;
            this.maxPoints = maxPoints;
            this.maxRadius = maxRadius;
            this.gpuGrid = gpuGrid;
            this.maxCells = maxCells;
        }

        public OrbitQuality next() {
            OrbitQuality[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    public static final int MIN_SIZE = 80;
    public static final int MAX_SIZE = 256;

    public Orientation orientation = Orientation.NORTH_UP;
    public FrameShape shape = FrameShape.SQUARE;
    public BezelStyle bezelStyle = BezelStyle.SOLID;
    public int minimapSize = 100;
    public double minimapX = 1.0;
    public double minimapY = 0.0;
    public boolean showBeacons = true;
    public boolean showNavMarkers = true;
    public boolean depthInMeters = false;
    public OrbitQuality orbitQuality = OrbitQuality.MEDIUM;
    public int safeDropBlocks = 4;

    public static final int MIN_SAFE_DROP = 2;
    public static final int MAX_SAFE_DROP = 8;

    public void setSafeDropBlocks(int n) {
        this.safeDropBlocks = Math.max(MIN_SAFE_DROP, Math.min(MAX_SAFE_DROP, n));
    }

    // How far the descent router will drop when nothing gentler reaches the goal. Never below
    // safeDropBlocks — a survivable tier under the safe tier is meaningless.
    public int maxSurvivableDrop = 16;

    public static final int MIN_SURVIVABLE_DROP = 4;
    public static final int MAX_SURVIVABLE_DROP = 28;

    public void setMaxSurvivableDrop(int n) {
        this.maxSurvivableDrop = Math.max(Math.max(MIN_SURVIVABLE_DROP, safeDropBlocks),
                Math.min(MAX_SURVIVABLE_DROP, n));
    }

    // How much area (blocks across) the 3D view may cover at full zoom-out. Wider settings use
    // coarser voxels so the sampled grid — and therefore performance — stays about the same.
    public int orbitAreaBlocks = 2048;

    // 4096 is still the widest LIVE-sampled view: Voxy hard-codes MAX_LOD_LAYER = 4 (16-block
    // cells) and never builds coarser, so 2048 is native and 4096 one cheap synthesis step —
    // deeper live synthesis was removed as slow and mostly empty. ORBIT_AREA_WHOLE is different in
    // kind: the whole mapped column rendered from AbyssSpanStore's background-built cache (native
    // LOD-4 reads, offline mips), never from live sampling.
    public static final int ORBIT_AREA_WHOLE = 16384;
    public static final int[] ORBIT_AREA_STEPS = {1024, 2048, 4096, ORBIT_AREA_WHOLE};

    // Snaps to the nearest allowed step (also clamps out-of-range/legacy values).
    public void setOrbitAreaBlocks(int blocks) {
        int best = ORBIT_AREA_STEPS[0];
        int bestD = Integer.MAX_VALUE;
        for (int step : ORBIT_AREA_STEPS) {
            int d = Math.abs(step - blocks);
            if (d < bestD) { bestD = d; best = step; }
        }
        this.orbitAreaBlocks = best;
    }

    // Optional 3D-view readout: which sector/LOD is in play, the sampled shifted-Y band, where the
    // returned voxels actually sit, and the point count vs the quality tier's cap.
    public boolean orbitStats = false;

    // Smooth (Surface-Nets iso-surface) 3D orbit rendering vs the legacy hard-cube splatting.
    // Only affects the CPU fallback path (used when the native GPU renderer is off/unavailable).
    public boolean smooth3d = true;

    // Master switch for the native GPU greedy-mesh 3D renderer. When on AND the native module loaded,
    // it draws the orbit view; otherwise the CPU path (cubes/Surface-Nets) renders as the fallback.
    public boolean gpuRender = true;

    // Shown in the UI as "Cave Maps", 0-75%. 0 keeps the solid render; above 0 the orbit view
    // draws terrain translucent and depth-sorted so cave structure inside the rock is visible.
    //
    // The field and the internal seeThrough/seeThroughAlpha names stay mechanism-named on purpose.
    // They say what the code does, they keep this key stable in saved configs, and they are what
    // keeps the translucency pass from reading as MapMode.CAVES, which is a different feature that
    // also has "cave" in its name.
    public int orbitTransparency = 0;

    // Cutaway: drop everything between the camera and the plane through the player, perpendicular
    // to the view. Looking down it takes the ceiling off; looking level it takes the near wall off.
    public boolean orbitCutaway = false;

    // How far along the view axis the cutaway plane sits, in blocks, relative to the focus. Positive
    // moves it away from the camera (deeper into the model), negative toward it. At whole-Abyss zoom
    // the plane pinned to the player could only ever cut at the player's own depth, which is one
    // slice of a 7000-block column; this makes the whole column reachable.
    public double orbitCutOffset = 0.0;
    public static final double MAX_CUT_OFFSET = 20000.0;

    /**
     * Usable strengths, and why they start so high. Per-layer alpha is 1 - strength/100, so the
     * background still visible through n surface layers is (1-alpha)^n. The cube path draws only
     * surface voxels, and reaching a cave crosses roughly 2-6 of them, so:
     * 25% leaves 6% showing through two layers — indistinguishable from solid.
     * 50% leaves 25%. 60% leaves 36%. 75% leaves 56%. 92% leaves 85%.
     * Anything below about 60 is a setting that appears to do nothing, so the steps skip it.
     */
    public static final int[] TRANSPARENCY_STEPS = {0, 60, 75, 85, 92};
    public static final int MAX_TRANSPARENCY = 92;

    /** Next strength in the cycle; wraps back to Off. */
    public static int nextTransparency(int current) {
        int i = 0;
        while (i < TRANSPARENCY_STEPS.length && TRANSPARENCY_STEPS[i] != current) i++;
        return TRANSPARENCY_STEPS[(i + 1) % TRANSPARENCY_STEPS.length];
    }

    // Capture loaded chunks into the mia-lods store. OFF by default: it is being introduced
    // alongside the existing data path and writes nothing the map reads yet, so it must be opt-in
    // until it has been proven against real terrain.
    public boolean lodIndexing = false;

    // Stage 7 de-risking probe: one box in the world at distance, to prove depth compositing works
    // with Sodium and Iris before a mesh pipeline is built on the assumption that it does. Draws
    // into the game view, so it must never be on by accident.

    // Stage 7: draw terrain from the LOD store into the world, meshed per section. Needs lodIndexing
    // to have captured something first. Off by default — it renders into the game view.
    /**
     * Import from Voxy automatically, once, shortly after joining a world.
     *
     * <p>Our store only learns terrain the client actually loads, because that is what it indexes.
     * Voxy's store is filled by its own ingest, which on a server running the Voxy plugin receives
     * streamed LOD data for ground the player has never visited — so Voxy legitimately knows more,
     * and the only bridge is an import. Leaving that on a button means coverage silently depends on
     * remembering to press it.
     *
     * <p>Costs one pass over Voxy's store per session. Re-importing is cheap and idempotent: the
     * store skips unchanged sections by content hash, and the last full run wrote 599,156 sections
     * for 4,167 genuinely new ones.
     */
    public boolean autoImportFromVoxy = true;

    /**
     * Show framerate on the HUD, with the LOD renderer's cost beside it when that is running.
     *
     * <p>The two belong together. Distance rendering is the thing most likely to cost frames here,
     * and a framerate with no idea what is being drawn cannot tell a heavy view from a slow machine
     * — which is the question anyone tuning the cascade or the layer span is actually asking.
     */
    public boolean showFps = true;


    /**
     * How many Abyss layers either side of your own to draw in the world, as one stacked shaft.
     *
     * <p>0 draws only the layer you are in, which is what the world actually contains. Above that
     * the renderer shows neighbouring layers displaced into the column the Abyss is meant to be.
     *
     * <p>Capped at {@link #MAX_LAYER_SPAN}, currently 1. The cap is not arbitrary and not a
     * placeholder for taste: this store holds 13 of the Abyss's 15 layers, and drawing all of them
     * at the present cell size would be roughly thirteen times the per-frame geometry of one, which
     * is an order of magnitude past what is already slow. Raising the cap belongs with the LOD
     * cascade that lets distant layers use coarser cells, not before it.
     */
    public int lodLayerSpan = 0;
    /**
     * Raised from 1 now the cascade can afford it. Eight layers either side reaches the rim from the
     * deepest layer anyone has stored, and at 480 blocks apart the far ones land in the 16-block-cell
     * band at a sixty-fourth of the faces of the near one.
     */
    public static final int MAX_LAYER_SPAN = 8;
    /** The steps the setting offers. Stepping one at a time to eight would be eight clicks. */
    public static final int[] LAYER_SPAN_STEPS = {0, 1, 2, 4, 8};

    public void setLodLayerSpan(int v) {
        lodLayerSpan = Math.max(0, Math.min(MAX_LAYER_SPAN, v));
    }

    /** The next offered span, wrapping. */
    public int nextLayerSpan() {
        for (int v : LAYER_SPAN_STEPS) if (v > lodLayerSpan) return v;
        return 0;
    }

    // Stage 6: draw the 2D map from the mia-loddy store instead of the Voxy engine. Selectable so the
    // two can be compared on the same view before either becomes the default. Falls back to Voxy
    // wherever the store cannot serve a tile, so turning it on can never blank the map.
    /**
     * Read the map from our own store rather than Voxy's.
     *
     * <p>Default since 0.1.20. Justified by measurement rather than preference: the two sources
     * agree on 100% of drawn pixels for coverage with none missing, heights match to better than
     * 99.9%, level 0 agrees to 0.04%, and scoring both folds against full detail rates them
     * equivalent. What settles it is that this store is the one the mod owns.
     *
     * <p>{@link com.mia.aperture.lod.LodIndexer#hasData()} gates it, so an empty store falls back
     * rather than rendering blank.
     */
    public boolean mapFromStore = true;

    public boolean trackHostiles = true;
    public boolean trackPlayers = true;
    public boolean trackPassive = false;
    public boolean mobLabels = false;
    public boolean mobList = false;

    public void setMinimapSize(int px) {
        this.minimapSize = Math.max(MIN_SIZE, Math.min(MAX_SIZE, px));
    }

    public void setMinimapPos(double fx, double fy) {
        this.minimapX = Math.max(0.0, Math.min(1.0, fx));
        this.minimapY = Math.max(0.0, Math.min(1.0, fy));
    }
}
