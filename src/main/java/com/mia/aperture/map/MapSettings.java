package com.mia.aperture.map;

public final class MapSettings {
    public enum Orientation { NORTH_UP, HEADING_UP }
    public enum FrameShape { SQUARE, ROUND }
    public enum MinimapCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

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

    // 3D see-through strength, 0-75%. 0 keeps the solid render. Above 0 the orbit view draws
    // terrain translucent and depth-sorted so cave structure inside the rock is visible.
    public int orbitTransparency = 0;

    // Capture loaded chunks into the mia-lods store. OFF by default: it is being introduced
    // alongside the existing data path and writes nothing the map reads yet, so it must be opt-in
    // until it has been proven against real terrain.
    public boolean lodIndexing = false;

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
