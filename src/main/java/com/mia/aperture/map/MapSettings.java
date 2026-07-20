package com.mia.aperture.map;

public final class MapSettings {
    public enum Orientation { NORTH_UP, HEADING_UP }
    public enum FrameShape { SQUARE, ROUND }
    public enum MinimapCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    public enum CaveMode { AUTO, ON, OFF }

    // 3D Orbit View quality tiers: texture resolution, point budget, and max splat radius.
    // Higher tiers look sharper but cost more per frame + memory; lower tiers keep weak PCs usable.
    public enum OrbitQuality {
        // textureSize drives GPU upload cost (size^2); maxPoints drives detail (done off-thread,
        // so it's cheap). Keep textures modest, push detail via points.
        POTATO("Potato", 768, 20000, 10),
        LOW("Low", 1024, 50000, 16),
        MEDIUM("Medium", 2048, 150000, 30),
        HIGH("High", 3072, 320000, 56),
        ULTRA("Ultra", 4096, 600000, 88);

        public final String label;
        public final int textureSize, maxPoints, maxRadius;

        OrbitQuality(String label, int textureSize, int maxPoints, int maxRadius) {
            this.label = label;
            this.textureSize = textureSize;
            this.maxPoints = maxPoints;
            this.maxRadius = maxRadius;
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
    public CaveMode caveMode = CaveMode.AUTO;
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
    public boolean smooth3d = true;

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
