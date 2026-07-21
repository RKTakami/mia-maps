package com.mia.aperture.map;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.AbyssUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// The 3D orbit view. Sampling + rasterization run on a BACKGROUND thread into an off-screen
// NativeImage + depth buffer; the render thread only bulk-copies the finished frame into the
// live texture and uploads it. So orbiting/zooming never blocks the game thread — higher quality
// tiers stay smooth (the image trails the camera by a frame or two).
public final class OrbitScene {
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("mia_aperture_mod", "orbit");
    private static final double FOV = Math.toRadians(70.0);
    private static final int EXTENT = 128;       // horizontal sampled edge (blocks) at zoom 1
    private static final double VERT_UP = 1.5;   // vertical extent above the player = horizontal * this
    private static final double VERT_DOWN = 1.5; // equal to UP -> player sits at the 50/50 line
    private static final int G_MAX = 128;        // max HORIZONTAL grid cells per axis (bounds cell size)
    // The GPU mesh path affords a larger grid than the CPU raster, so it can hold a FINER LOD over the
    // same area. Bounds the greedy-mesh cost (re-meshed only on pan/zoom, on the render thread).
    private static final int G_MAX_GPU = 256;
    // Voxy stores NOTHING at level 5 (MAX_LOD_LAYER = 4), so the live GPU path must never sample it —
    // it comes back empty. The whole-Abyss span model is the right source past this zoom (a follow-up).
    private static final int GPU_MAX_LVL = 4;
    // Voxy stores nothing coarser than level 4 (WorldEngine.MAX_LOD_LAYER), so with the 128-cell
    // grid, 2048 blocks is the widest NATIVE view. Level 5 (32-block voxels) reaches the 4096
    // setting and is synthesized from level 4 in one cheap step — that is the ceiling worth
    // having. Do NOT raise MapGeometry.MAX_LVL — that governs the 2D map's display level.
    private static final int ORBIT_MAX_LVL = 5;
    // Smooth (Surface-Nets) meshing rounds off block ledges, so it only helps at WIDE zoom where
    // cubes are big chunky blobs and you can't resolve individual blocks anyway. At finer LOD the
    // crisp cube path is what makes the map usable for navigation (reading a block path down a
    // cliff), so mesh only from this level up; below it, cubes.
    private static final int SMOOTH_MIN_LVL = 3;   // cell >= 8 blocks
    private static final float SATURATION = 1.25f;
    private static final float CONTRAST = 1.08f;
    private static final float LX = 0.321f, LY = 0.919f, LZ = 0.230f;
    private static final float AMBIENT = 0.4f;

    // Cube faces: {normalX,Y,Z, tangent1X,Y,Z, tangent2X,Y,Z} (unit axes).
    private static final double[][] FACES = {
        {1, 0, 0, 0, 1, 0, 0, 0, 1}, {-1, 0, 0, 0, 1, 0, 0, 0, 1},
        {0, 1, 0, 1, 0, 0, 0, 0, 1}, {0, -1, 0, 1, 0, 0, 0, 0, 1},
        {0, 0, 1, 1, 0, 0, 0, 1, 0}, {0, 0, -1, 1, 0, 0, 0, 1, 0},
    };

    private OrbitScene() {}

    // ---- desired state: render thread -> worker ----
    private static volatile OrbitCamera dCam;
    private static volatile double dZoom;
    private static volatile MapSettings.OrbitQuality dQuality;

    // TEMP Task 5 verify: worker stashes the camera used for the GPU grid so the render thread
    // can build a matching MVP. Throwaway; Task 6 does the clean rewire.
    private static volatile boolean gpuReady = false;
    private static volatile double gpuFocusX, gpuFocusY, gpuFocusZ;
    private static volatile double gpuYaw, gpuPitch, gpuDist;
    private static long gpuGridSig = Long.MIN_VALUE;
    private static VoxelCloud.Grid gpuGridCache;
    private static volatile long lastRenderMs;
    private static volatile int cloudSize;
    // Actual texture edge to render at: the tier value capped to ~1.5x the on-screen 3D-view
    // square, so we never upload detail the monitor can't show. Set by the render thread.
    private static volatile int desiredTex = 2048;
    private static final double SUPERSAMPLE = 1.5;

    public enum XrayMode { OFF, GHOST, CAVE_ONLY }
    private static volatile XrayMode xrayMode = XrayMode.OFF;
    private static final float GHOST_ALPHA = 0.28f;

    public static XrayMode xrayMode() { return xrayMode; }
    public static void setXrayMode(XrayMode m) { xrayMode = m; }

    // ---- worker back-buffer + published-frame handoff (guarded by SWAP) ----
    private static final Object SWAP = new Object();
    private static NativeImage buf;      // worker fills this; render copies it under SWAP
    private static float[] bufDepth;
    private static int bufSize = -1;
    private static boolean frontReady;
    private static double[] fCel, fB;
    private static double fFocal, fFx, fFy, fFz;
    private static int fSize;
    private static long fSig;
    private static long frameCounter;
    private static Thread worker;

    // ---- displayed state (render thread only) ----
    private static DynamicTexture texture;
    private static int texSize = -1;
    private static int size = 2048;
    private static float[] depthBuf;
    private static double[] hudCel, hudB;
    private static double hudFocal, hudFx, hudFy, hudFz;
    private static long displayedSig = Long.MIN_VALUE;
    private static long lastUploadMs;
    private static final long UPLOAD_INTERVAL_MS = 100;  // cap texture uploads to ~10/sec

    // ---- worker-owned cloud ----
    private static List<VoxelCloud.Point> cloud;
    private static long cloudSig = Long.MIN_VALUE;
    private static long producedSig = Long.MIN_VALUE;
    private static boolean cloudWhole;
    private static int wholeLevel;
    // Smooth (Surface-Nets) mesh for the live path; null means the legacy cube renderer draws
    // (smooth3d off, or the whole-Abyss path which still splats cube points this pass). Tracks the
    // same cache generation as `cloud`; cloudSmooth lets a live smooth3d toggle invalidate it.
    private static OrbitMesher.Mesh mesh;
    private static boolean cloudSmooth;

    public static int size() { return size; }

    public static int lastCloudSize() { return cloudSize; }

    // What the last sample actually covered — powers the optional "3D Stats" overlay
    // (MapSettings.orbitStats). All in SHIFTED coords (shiftedY = abyssDepth + 3840, so the rim
    // is ~3840): the sector whose shift is in play, the chosen LOD, the focus, the sampled band,
    // and the min..max Y of the voxels that actually came back.
    public static volatile int statFocusY, statBandLo, statBandHi, statLvl, statSector;
    public static volatile int statVoxMinY = Integer.MAX_VALUE, statVoxMaxY = Integer.MIN_VALUE;

    // Camera-space depth of the displayed frame at texture pixel (sx,sy), for occluding overlays.
    public static float depthAt(int sx, int sy) {
        if (depthBuf == null || sx < 0 || sy < 0 || sx >= size || sy >= size) return Float.MAX_VALUE;
        return depthBuf[sy * size + sx];
    }

    // Project a focus-relative offset through the DISPLAYED frame's camera -> texture-space Screen.
    public static BeaconGeometry.Screen projectHud(double ox, double oy, double oz) {
        if (hudB == null) return new BeaconGeometry.Screen(false, size / 2, size / 2, 0, 0, 0);
        return BeaconGeometry.project(hudFx + ox - hudCel[0], hudFy + oy - hudCel[1], hudFz + oz - hudCel[2],
                hudB[0], hudB[1], hudB[2], hudB[3], hudB[4], hudB[5], hudB[6], hudB[7], hudB[8], hudFocal, size, size);
    }

    // Project an ABSOLUTE point in the Abyss's shifted column (see MapGeometry.toShiftedColumn).
    // Overlays whose source data is world-space must come through here rather than subtracting a
    // world-space focus themselves: a world delta only equals a shifted delta within one section,
    // and sections are just 480 blocks of depth apart.
    public static BeaconGeometry.Screen projectShifted(double sx, double sy, double sz) {
        if (hudB == null) return new BeaconGeometry.Screen(false, size / 2, size / 2, 0, 0, 0);
        return projectHud(sx - hudFx, sy - hudFy, sz - hudFz);
    }

    // The shifted-column focus of the DISPLAYED frame, for overlays that need to compare a point's
    // layer against the one being viewed.
    public static double hudFocusShiftedY() {
        return hudFy;
    }

    // Un-project a texture pixel to a world/shifted OFFSET from the focus, or null if empty.
    public static double[] unprojectOffset(int texX, int texY) {
        if (hudB == null) return null;
        float d = depthAt(texX, texY);
        if (d >= Float.MAX_VALUE) return null;
        double zc = d;
        double xc = (texX - size / 2.0) / hudFocal * zc;
        double yc = (size / 2.0 - texY) / hudFocal * zc;
        double relx = zc * hudB[0] + xc * (-hudB[6]) + yc * hudB[3];
        double rely = zc * hudB[1] + xc * (-hudB[7]) + yc * hudB[4];
        double relz = zc * hudB[2] + xc * (-hudB[8]) + yc * hudB[5];
        return new double[]{ hudCel[0] + relx - hudFx, hudCel[1] + rely - hudFy, hudCel[2] + relz - hudFz };
    }

    public static double cameraDistance(double zoom) {
        return EXTENT * zoom * 2.0;
    }

    // Highest zoom that keeps the sampled area within `areaBlocks` (extentXZ = EXTENT * zoom).
    // The Whole Abyss step must frame the full ~8k-block column vertically, so its ceiling comes
    // from the band height rather than a horizontal area.
    public static double maxZoom(int areaBlocks) {
        if (areaBlocks == MapSettings.ORBIT_AREA_WHOLE) {
            return Math.ceil((MapGeometry.ABYSS_SHIFTED_Y_TOP - MapGeometry.ABYSS_SHIFTED_Y_BOTTOM + 512)
                    / (double) EXTENT);
        }
        return Math.max(1.0, areaBlocks / (double) EXTENT);
    }

    // Must run on the RENDER thread: releasing the texture is a GL call and GL rejects every
    // other thread. Callers on event/network threads hop via Minecraft.execute first (see the
    // DISCONNECT handler in MiaApertureModClient).
    public static void reset() {
        dCam = null;
        synchronized (SWAP) { frontReady = false; }
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
            texture = null;
        }
        texSize = -1;
        depthBuf = null;
        hudB = null;
        displayedSig = Long.MIN_VALUE;
        xrayMode = XrayMode.OFF;
    }

    // Render thread. Publishes the desired camera, adopts any finished worker frame, returns the
    // texture to blit. The heavy work happens on the worker; here we only bulk-copy + upload.
    public static Identifier render(OrbitCamera cam, double zoom, MapSettings.OrbitQuality quality) {
        dCam = cam; dZoom = zoom; dQuality = quality; lastRenderMs = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        int viewSquare = Math.min(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        desiredTex = Math.max(256, Math.min(quality.textureSize, (int) Math.ceil(viewSquare * SUPERSAMPLE)));
        ensureWorker();

        boolean uploaded = false;
        long now = System.currentTimeMillis();
        synchronized (SWAP) {
            if (frontReady && now - lastUploadMs >= UPLOAD_INTERVAL_MS) {
                lastUploadMs = now;
                if (texture == null || fSize != texSize) {
                    if (texture != null) mc.getTextureManager().release(TEXTURE);
                    texture = new DynamicTexture(TEXTURE.toString(), fSize, fSize, true);
                    mc.getTextureManager().register(TEXTURE, texture);
                    texSize = fSize;
                    depthBuf = null;
                }
                size = fSize;
                NativeImage dst = texture.getPixels();
                if (dst != null && buf != null) dst.copyFrom(buf);
                if (depthBuf == null || depthBuf.length != bufDepth.length) depthBuf = new float[bufDepth.length];
                System.arraycopy(bufDepth, 0, depthBuf, 0, bufDepth.length);
                hudCel = fCel; hudB = fB; hudFocal = fFocal; hudFx = fFx; hudFy = fFy; hudFz = fFz;
                displayedSig = fSig;
                frontReady = false;
                uploaded = true;
            }
        }
        if (texture == null) {
            // Placeholder so the blit has a registered texture before the first frame lands.
            texture = new DynamicTexture(TEXTURE.toString(), 16, 16, true);
            mc.getTextureManager().register(TEXTURE, texture);
            texSize = 16;
            uploaded = true;
        }
        // TEMP Task 5/7 verify: when the GPU path is drawing, it owns the texture entirely — skip the
        // CPU upload so the coarse CPU render never flashes through while a new GPU mesh rebuilds (the
        // render keeps showing the previous GPU mesh until the new one lands). Throwaway; Task 6 cleans.
        boolean gpuActive = MapNative.available() && gpuReady && texture != null && texSize > 16;
        if (uploaded && !gpuActive) texture.upload();  // only when the image changed — never every frame
        if (gpuActive) {
            float[] mvp = MapMatrix.orbit(gpuFocusX, gpuFocusY, gpuFocusZ, gpuYaw, gpuPitch, gpuDist,
                    (float) Math.toRadians(70), 1f, 1f, 20000f);
            int glId = ((com.mojang.blaze3d.opengl.GlTexture) texture.getTexture()).glId();
            OrbitGpuRenderer.render(mvp, glId, texSize);
        }
        return TEXTURE;
    }

    private static synchronized void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(OrbitScene::loop, "MIA-Orbit-Raster");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    private static void loop() {
        while (true) {
            try {
                OrbitCamera cam = dCam;
                MapSettings.OrbitQuality quality = dQuality;
                if (cam == null || quality == null || System.currentTimeMillis() - lastRenderMs > 2000) {
                    Thread.sleep(80);
                    continue;
                }
                synchronized (SWAP) {
                    if (frontReady) { /* previous frame not yet consumed */ }
                }
                boolean pending;
                synchronized (SWAP) { pending = frontReady; }
                if (pending) { Thread.sleep(3); continue; }

                double zoom = dZoom;
                long sig = computeSig(cam, zoom, quality);
                if (sig == producedSig) { Thread.sleep(12); continue; }
                if (buildFrame(cam, zoom, quality)) producedSig = sig;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                System.err.println("[MIA Maps] orbit raster failed: " + t);
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
        }
    }

    private static boolean wholeMode() {
        return com.mia.aperture.client.MiaApertureModClient.mapSettings.orbitAreaBlocks
                == MapSettings.ORBIT_AREA_WHOLE;
    }

    private static long computeSig(OrbitCamera cam, double zoom, MapSettings.OrbitQuality quality) {
        int sector = AbyssUtil.getSection(cam.focusX);
        int fx = (int) Math.floor(cam.focusX - (double) (sector << 14));
        int fy = (int) Math.floor(cam.focusY + (240 - sector * 30) * 16.0);
        int fz = (int) Math.floor(cam.focusZ);
        int extentXZ = Math.max(16, (int) Math.round(EXTENT * zoom));
        boolean whole = wholeMode();
        // In whole mode the frame depends on the cache generation, not the sampled region —
        // a new snapshot (progressive build, dirty refresh) must re-rasterize.
        long snapSeq = whole ? AbyssSpanStore.current().seq() : 0;
        return Objects.hash(fx, fy, fz, extentXZ, desiredTex,
                (int) Math.round(cam.yawDeg), (int) Math.round(cam.pitchDeg), (int) Math.round(cam.distance),
                xrayMode.ordinal(), whole, snapSeq);
    }

    // Worker: sample (if the cloud region changed) + rasterize into buf/bufDepth, then publish.
    private static boolean buildFrame(OrbitCamera cam, double zoom, MapSettings.OrbitQuality quality) {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        Minecraft mc = Minecraft.getInstance();
        if (rs == null || mc.level == null) return false;
        MapColorSource colors = MapCompositor.colorSource();
        if (colors == null) return false;
        var engine = rs.getEngine();

        int sz = desiredTex;
        int extentXZ = Math.max(16, (int) Math.round(EXTENT * zoom));
        int extentUp = Math.max(8, (int) Math.round(EXTENT * zoom * VERT_UP));
        int extentDown = Math.max(8, (int) Math.round(EXTENT * zoom * VERT_DOWN));
        int lvl = 0;
        while ((extentXZ >> lvl) > G_MAX && lvl < ORBIT_MAX_LVL) lvl++;

        int sector = AbyssUtil.getSection(cam.focusX);
        double focusXExact = cam.focusX - (double) (sector << 14);
        double focusYExact = cam.focusY + (240 - sector * 30) * 16.0;
        double focusZExact = cam.focusZ;
        int shiftedFocusX = (int) Math.floor(focusXExact);
        int shiftedFocusY = (int) Math.floor(focusYExact);
        int focusZ = (int) Math.floor(focusZExact);

        // Trim the vertical sample to the Abyss's shifted-Y band. A wide view asks for ~24k blocks
        // each way, but the whole Abyss is only ~7.8k tall — the rest is empty sky/void that still
        // costs a full coarser+downsample probe per section to prove empty. Clamping here also
        // makes max zoom frame exactly rim-to-floor, i.e. every layer at once. Must happen before
        // the cloud signature below so the cache keys on the trimmed extents.
        int[] vert = MapGeometry.clampVerticalToAbyss(shiftedFocusY, extentUp, extentDown, 8);
        extentUp = vert[0];
        extentDown = vert[1];

        statFocusY = shiftedFocusY;
        statBandLo = shiftedFocusY - extentDown;
        statBandHi = shiftedFocusY + extentUp;
        statLvl = lvl;
        statSector = sector;

        boolean whole = wholeMode();
        // TEMP Task 5/7 verify: build the GPU grid (live box OR the whole-Abyss span model), submit it
        // to the GPU renderer on this WORKER thread (meshing is off the render thread), and stash the
        // camera so the render thread can draw it into the map texture.
        gpuReady = false;
        if (MapNative.available()) {
            long gsig;
            if (whole) {
                // Whole-Abyss: read the complete pre-built column model (AbyssSpanStore), not a box, so
                // the entire rim renders with no sample-box cutoff. Pick the finest mip within budget.
                AbyssModelBuilder.ensureStarted();
                AbyssSpanStore.Snapshot snap = AbyssSpanStore.current();
                int lvlW = AbyssSpanStore.LEVELS - 1;
                for (int l = 0; l < AbyssSpanStore.LEVELS; l++) {
                    if (snap.surfaceCounts()[l] <= quality.maxPoints) { lvlW = l; break; }
                }
                gsig = Objects.hash(0x5EAB, snap.seq(), lvlW);
                if (gsig != gpuGridSig || gpuGridCache == null) {
                    gpuGridCache = wholeGrid(snap.level(lvlW), lvlW);
                    gpuGridSig = gsig;
                }
            } else {
                // The camera sits ~2x extentXZ from the focus at a 70deg FOV, so the visible frustum
                // footprint is ~3x extentXZ. Sample that wider box or the box edges show as hard walls.
                int gpuExtentXZ = extentXZ * 3;
                int[] gpuVert = MapGeometry.clampVerticalToAbyss(shiftedFocusY, extentUp * 3, extentDown * 3, 8);
                int gpuUp = gpuVert[0], gpuDown = gpuVert[1];
                int gpuLvl = 0;
                while ((gpuExtentXZ >> gpuLvl) > G_MAX_GPU && gpuLvl < GPU_MAX_LVL) gpuLvl++;
                // Keep the grid within budget at the lod-4 ceiling (Voxy has no lod 5).
                gpuExtentXZ = Math.min(gpuExtentXZ, G_MAX_GPU << gpuLvl);
                gsig = Objects.hash(shiftedFocusX, shiftedFocusY, focusZ, gpuExtentXZ, gpuUp, gpuDown, gpuLvl);
                if (gsig != gpuGridSig || gpuGridCache == null) {
                    gpuGridCache = VoxelCloud.sampleGrid(engine, colors, shiftedFocusX, shiftedFocusY,
                            focusZ, gpuExtentXZ, gpuUp, gpuDown, gpuLvl);
                    gpuGridSig = gsig;
                }
            }
            // Worker thread: mesh off the render thread. submit() meshes once per region (and retries
            // until the GL context exists); the render thread only uploads the staged mesh + draws.
            if (gpuGridCache != null) {
                OrbitGpuRenderer.submit(gpuGridCache, gsig);
                gpuFocusX = focusXExact; gpuFocusY = focusYExact; gpuFocusZ = focusZExact;
                gpuYaw = cam.yawDeg; gpuPitch = cam.pitchDeg; gpuDist = cam.distance;
                gpuReady = true;
            }
        }
        boolean smooth = com.mia.aperture.client.MiaApertureModClient.mapSettings.smooth3d;
        long cs = whole
                ? Objects.hash(0x5EAB, AbyssSpanStore.current().seq(), quality.maxPoints)
                : Objects.hash(shiftedFocusX, shiftedFocusY, focusZ, extentXZ, extentUp, extentDown, lvl);
        if (cloud == null || cs != cloudSig || whole != cloudWhole || smooth != cloudSmooth) {
            if (whole) {
                // Whole-Abyss reads the span model, not a dense grid, so it stays on the cube
                // renderer this pass (smooth-mesh assembly from spans is a scoped follow-up).
                AbyssModelBuilder.ensureStarted();
                cloud = buildWholeCloud(quality.maxPoints);
                mesh = null;
            } else if (smooth && lvl >= SMOOTH_MIN_LVL) {
                VoxelCloud.Grid grid = VoxelCloud.sampleGrid(engine, colors, shiftedFocusX, shiftedFocusY,
                        focusZ, extentXZ, extentUp, extentDown, lvl);
                mesh = OrbitMesher.build(grid.opaque(), grid.argb(), grid.gX(), grid.gY(), grid.gZ(),
                        grid.cell(), grid.originCellX(), grid.originCellY(), grid.originCellZ());
                cloud = List.of();
            } else {
                cloud = VoxelCloud.sample(engine, colors, shiftedFocusX, shiftedFocusY, focusZ,
                        extentXZ, extentUp, extentDown, lvl, quality.maxPoints);
                mesh = null;
            }
            cloudWhole = whole;
            cloudSmooth = smooth;
            cloudSig = cs;
            cloudSize = mesh != null ? mesh.tris().length / 3 : cloud.size();
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            if (mesh != null) {
                float[] pos = mesh.positions();
                for (int i = 1; i < pos.length; i += 3) {
                    int y = (int) pos[i];
                    if (y < lo) lo = y;
                    if (y > hi) hi = y;
                }
            } else {
                for (VoxelCloud.Point p : cloud) {
                    int y = (int) p.y();
                    if (y < lo) lo = y;
                    if (y > hi) hi = y;
                }
            }
            statVoxMinY = lo;
            statVoxMaxY = hi;
        }
        // Stats must reflect the cache on EVERY frame, not only on cloud rebuilds — the shared
        // assignments above are live-sampler values and would flicker back in between rebuilds
        // while orbiting.
        if (whole) {
            statLvl = 4 + wholeLevel;
            statBandLo = MapGeometry.ABYSS_SHIFTED_Y_BOTTOM;
            statBandHi = MapGeometry.ABYSS_SHIFTED_Y_TOP;
        }

        if (buf == null || bufSize != sz) {
            if (buf != null) buf.close();
            buf = new NativeImage(sz, sz, false);
            bufDepth = new float[sz * sz];
            bufSize = sz;
        }

        double focal = (sz / 2.0) / Math.tan(FOV / 2.0);
        Arrays.fill(bufDepth, Float.MAX_VALUE);
        buf.fillRect(0, 0, sz, sz, 0x00000000);
        OrbitCamera c = new OrbitCamera(focusXExact, focusYExact, focusZExact,
                cam.yawDeg, cam.pitchDeg, cam.distance);
        double[] cel = c.cameraPos();
        double[] b = c.basis();
        rasterizeInto(buf, bufDepth, sz, cel, b, focal);

        synchronized (SWAP) {
            fCel = cel; fB = b; fFocal = focal; fFx = focusXExact; fFy = focusYExact; fFz = focusZExact;
            fSize = sz; fSig = ++frameCounter;
            frontReady = true;
        }
        return true;
    }

    // Whole-Abyss cloud: read the cached span model instead of sampling Voxy. Picks the finest
    // mip whose surface count fits the quality tier's point budget (64-block cells are at or below
    // one screen pixel at full zoom-out, so coarseness is invisible there), hard-capping at the
    // budget if even the coarsest level exceeds it.
    private static List<VoxelCloud.Point> buildWholeCloud(int maxPoints) {
        AbyssSpanStore.Snapshot snap = AbyssSpanStore.current();
        int level = AbyssSpanStore.LEVELS - 1;
        for (int l = 0; l < AbyssSpanStore.LEVELS; l++) {
            if (snap.surfaceCounts()[l] <= maxPoints) { level = l; break; }
        }
        int cellSize = AbyssSpanStore.cellSize(level);
        java.util.ArrayList<VoxelCloud.Point> pts = new java.util.ArrayList<>(
                Math.min(maxPoints, snap.surfaceCounts()[level]));
        AbyssSpanStore.forEachSurface(snap.level(level), (x, y, z, color, faces) -> {
            if (pts.size() >= maxPoints) return;
            pts.add(new VoxelCloud.Point(
                    (x + 0.5) * cellSize, (y + 0.5) * cellSize, (z + 0.5) * cellSize,
                    color, cellSize, 0f, 1f, 0f, faces, false));
        });
        wholeLevel = level;
        return pts;
    }

    // Materialize a whole-Abyss span-model mip into the dense occupancy+colour grid the mesher
    // consumes. Fills every solid cell of every column's spans (the greedy mesher keeps only exposed
    // faces); bounds come from the data, so the grid is exactly the explored column extent, with no
    // sample-box cutoff. Returns null if empty or beyond a safety size (caller falls back to cubes).
    private static VoxelCloud.Grid wholeGrid(Map<Integer, AbyssSpanStore.Column> map, int level) {
        if (map == null || map.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Map.Entry<Integer, AbyssSpanStore.Column> e : map.entrySet()) {
            int x = AbyssSpanStore.keyX(e.getKey()), z = AbyssSpanStore.keyZ(e.getKey());
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
            for (int s : e.getValue().spans()) {
                int b = SpanMath.spanBottom(s), t = SpanMath.spanTop(s);
                if (b < minY) minY = b;
                if (t > maxY) maxY = t;
            }
        }
        if (maxY < minY) return null;
        long gX = maxX - minX + 1L, gY = maxY - minY + 1L, gZ = maxZ - minZ + 1L;
        long n = gX * gY * gZ;
        if (n <= 0 || n > 48_000_000L) return null;
        int igX = (int) gX, igZ = (int) gZ;
        boolean[] opaque = new boolean[(int) n];
        int[] argb = new int[(int) n];
        for (Map.Entry<Integer, AbyssSpanStore.Column> e : map.entrySet()) {
            int x = AbyssSpanStore.keyX(e.getKey()) - minX, z = AbyssSpanStore.keyZ(e.getKey()) - minZ;
            AbyssSpanStore.Column c = e.getValue();
            for (int i = 0; i < c.spans().length; i++) {
                int b = SpanMath.spanBottom(c.spans()[i]), t = SpanMath.spanTop(c.spans()[i]);
                int color = c.colors()[i];
                for (int y = b; y <= t; y++) {
                    int idx = ((y - minY) * igZ + z) * igX + x;
                    opaque[idx] = true;
                    argb[idx] = color;
                }
            }
        }
        return new VoxelCloud.Grid(opaque, argb, igX, (int) gY, igZ,
                AbyssSpanStore.cellSize(level), minX, minY, minZ);
    }

    // Draw each surface voxel as an axis-aligned cube: its up-to-3 camera-facing exposed faces,
    // each flat-shaded by its own face normal. Writes into `img` + `depth` (size sz).
    private static void rasterizeInto(NativeImage img, float[] depth, int sz,
                                      double[] cel, double[] b, double focal) {
        if (mesh != null) {
            drawMesh(img, depth, sz, cel, b, focal, mesh);
            return;
        }
        List<VoxelCloud.Point> pts = cloud;
        if (pts == null) return;
        // The cave classifier is a live-sampler feature; cache points carry covered=false, so in
        // whole mode CAVE_ONLY would render nothing and GHOST everything translucent. Force OFF.
        XrayMode mode = cloudWhole ? XrayMode.OFF : xrayMode;
        if (mode == XrayMode.CAVE_ONLY) {
            for (VoxelCloud.Point p : pts) if (p.covered()) drawCube(img, depth, sz, cel, b, focal, p, 1.0f);
        } else if (mode == XrayMode.GHOST) {
            for (VoxelCloud.Point p : pts) if (p.covered()) drawCube(img, depth, sz, cel, b, focal, p, 1.0f);
            for (VoxelCloud.Point p : pts) if (!p.covered()) drawCube(img, depth, sz, cel, b, focal, p, GHOST_ALPHA);
        } else {
            for (VoxelCloud.Point p : pts) drawCube(img, depth, sz, cel, b, focal, p, 1.0f);
        }
    }

    // Draw a smooth mesh: project each triangle's 3 vertices and fill via the existing fillTri.
    // Flat-shaded per triangle by its averaged vertex normal (the vertices already carry smooth
    // gradient normals). No back-face cull — the z-buffer resolves visibility.
    private static void drawMesh(NativeImage img, float[] depth, int sz,
                                 double[] cel, double[] b, double focal, OrbitMesher.Mesh m) {
        float[] pos = m.positions(), nrm = m.normals();
        int[] col = m.colors(), tri = m.tris();
        double[] sx = new double[3], sy = new double[3];
        for (int i = 0; i < tri.length; i += 3) {
            int a = tri[i], bb = tri[i + 1], c = tri[i + 2];
            int[] vi = {a, bb, c};
            double depthSum = 0;
            boolean ok = true;
            for (int k = 0; k < 3; k++) {
                int v = vi[k];
                BeaconGeometry.Screen s = BeaconGeometry.project(
                        pos[v * 3] - cel[0], pos[v * 3 + 1] - cel[1], pos[v * 3 + 2] - cel[2],
                        b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], focal, sz, sz);
                if (s.depth() <= 0.01) { ok = false; break; }
                sx[k] = s.x(); sy[k] = s.y(); depthSum += s.depth();
            }
            if (!ok) continue;
            float nx = (nrm[a * 3] + nrm[bb * 3] + nrm[c * 3]) / 3f;
            float ny = (nrm[a * 3 + 1] + nrm[bb * 3 + 1] + nrm[c * 3 + 1]) / 3f;
            float nz = (nrm[a * 3 + 2] + nrm[bb * 3 + 2] + nrm[c * 3 + 2]) / 3f;
            float ndotl = Math.max(0f, nx * LX + ny * LY + nz * LZ);
            float light = AMBIENT + (1f - AMBIENT) * ndotl;
            int base = ColorMath.punch(col[a], SATURATION, CONTRAST);
            int color = 0xFF000000 | (ColorMath.shade(base, light) & 0xFFFFFF);
            float z = (float) (depthSum / 3.0);
            fillTri(img, depth, sz, sx[0], sy[0], sx[1], sy[1], sx[2], sy[2], z, color, 1.0f);
        }
    }

    private static void drawCube(NativeImage img, float[] depth, int sz,
                                 double[] cel, double[] b, double focal, VoxelCloud.Point p, float alpha) {
        double[] sx = new double[4], sy = new double[4];
        double h = p.cellSize() * 0.5;
        int base = ColorMath.punch(p.argb(), SATURATION, CONTRAST);
        int faceBits = p.faces();
        for (int fi = 0; fi < FACES.length; fi++) {
            if ((faceBits & (1 << fi)) == 0) continue;
            double[] f = FACES[fi];
            double nfx = f[0], nfy = f[1], nfz = f[2];
            if (nfx * (cel[0] - p.x()) + nfy * (cel[1] - p.y()) + nfz * (cel[2] - p.z()) <= 0) continue;
            double fcx = p.x() + nfx * h, fcy = p.y() + nfy * h, fcz = p.z() + nfz * h;
            double t1x = f[3], t1y = f[4], t1z = f[5], t2x = f[6], t2y = f[7], t2z = f[8];
            double depthSum = 0;
            boolean ok = true;
            for (int k = 0; k < 4; k++) {
                double su = ((k == 1 || k == 2) ? h : -h);
                double sv = ((k >= 2) ? h : -h);
                double wx = fcx + t1x * su + t2x * sv;
                double wy = fcy + t1y * su + t2y * sv;
                double wz = fcz + t1z * su + t2z * sv;
                BeaconGeometry.Screen s = BeaconGeometry.project(wx - cel[0], wy - cel[1], wz - cel[2],
                        b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], focal, sz, sz);
                if (s.depth() <= 0.01) { ok = false; break; }
                sx[k] = s.x(); sy[k] = s.y(); depthSum += s.depth();
            }
            if (!ok) continue;
            float z = (float) (depthSum / 4.0);
            float ndotl = Math.max(0f, (float) (nfx * LX + nfy * LY + nfz * LZ));
            float light = AMBIENT + (1f - AMBIENT) * ndotl;
            int col = 0xFF000000 | (ColorMath.shade(base, light) & 0xFFFFFF);
            fillTri(img, depth, sz, sx[0], sy[0], sx[1], sy[1], sx[2], sy[2], z, col, alpha);
            fillTri(img, depth, sz, sx[0], sy[0], sx[2], sy[2], sx[3], sy[3], z, col, alpha);
        }
    }

    // Flat-shaded, flat-depth triangle fill with z-buffer (barycentric, both windings).
    private static void fillTri(NativeImage img, float[] depth, int sz,
                                double x0, double y0, double x1, double y1,
                                double x2, double y2, float z, int color, float alpha) {
        int minX = (int) Math.max(0, Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = (int) Math.min(sz - 1, Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = (int) Math.max(0, Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = (int) Math.min(sz - 1, Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6) return;
        for (int py = minY; py <= maxY; py++) {
            for (int px = minX; px <= maxX; px++) {
                double w0 = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1);
                double w1 = (x0 - x2) * (py - y2) - (y0 - y2) * (px - x2);
                double w2 = (x1 - x0) * (py - y0) - (y1 - y0) * (px - x0);
                boolean inside = (w0 >= 0 && w1 >= 0 && w2 >= 0) || (w0 <= 0 && w1 <= 0 && w2 <= 0);
                if (!inside) continue;
                int di = py * sz + px;
                if (z >= depth[di]) continue;
                depth[di] = z;
                if (alpha >= 1.0f) {
                    img.setPixel(px, py, color);
                } else {
                    img.setPixel(px, py, blendArgb(img.getPixel(px, py), color, alpha));
                }
            }
        }
    }

    // Lerp src over dst by alpha, keeping full opacity. Channel order is whatever the buffer uses;
    // the mix is order-agnostic since both operands share it.
    private static int blendArgb(int dst, int src, float a) {
        int dr = (dst >> 16) & 0xFF, dg = (dst >> 8) & 0xFF, db = dst & 0xFF;
        int sr = (src >> 16) & 0xFF, sg = (src >> 8) & 0xFF, sb = src & 0xFF;
        int r = (int) (dr * (1 - a) + sr * a);
        int g = (int) (dg * (1 - a) + sg * a);
        int bl = (int) (db * (1 - a) + sb * a);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
