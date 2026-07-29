package com.mia.aperture.map;

import com.mojang.blaze3d.platform.NativeImage;
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
    // Voxy stores NOTHING at level 5 (MAX_LOD_LAYER = 4), so the live GPU path must never sample it —
    // it comes back empty. The whole-Abyss span model is the right source past this zoom.
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
    // MapMode.CAVES: how far below the focus the carved slab reaches (half that above). Matches the
    // 2D slice so both views agree on what "your layer" means.
    private static final int CAVE_SLAB_BLOCKS = CaveShading.SLICE_BLOCKS;
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
    // Cascade shells, innermost first, each cached against its OWN snapped key so the wide coarse
    // shell survives the focus moves that force the fine one to resample.
    private static VoxelCloud.Grid[] gpuShellGrids;
    private static long[] gpuShellSigs;
    private static java.util.List<OrbitLod.Shell> gpuShellPlan;
    // Two is enough for the worked case (4blk inner + 32blk outer at Area 2048); more shells mean
    // more seams to hide for diminishing returns.
    private static final int MAX_SHELLS = 2;
    private static volatile long lastRenderMs;
    private static volatile int cloudSize;
    // Actual texture edge to render at: the tier value capped to ~1.5x the on-screen 3D-view
    // square, so we never upload detail the monitor can't show. Set by the render thread.
    private static volatile int desiredTex = 2048;
    private static final double SUPERSAMPLE = 1.5;

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

    /** Whether a real frame has landed, as opposed to the 16x16 placeholder made before the first. */
    public static boolean hasFrame() { return texSize > 16; }

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
        // No geometry under the cursor. This is NOT rare: the depth buffer belongs to the CPU raster,
        // while what's on screen is usually the GPU mesh, and the two cover different ground (the CPU
        // cloud is capped at quality.maxPoints and sampled at `lvl`/`extentXZ`, the GPU mesh at
        // `gpuLvl`/`gpuExtentXZ`). So a right-click on terrain that is plainly visible could miss the
        // depth buffer and be silently discarded — the view simply would not move. Fall back to the
        // plane through the current focus, perpendicular to the view, so a click always pans somewhere
        // sensible. The camera sits exactly `distance` back along forward from the focus, so the
        // focus plane is at that camera-space depth.
        double zc;
        if (d >= Float.MAX_VALUE) {
            double dx = hudFx - hudCel[0], dy = hudFy - hudCel[1], dz = hudFz - hudCel[2];
            zc = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (zc <= 0) return null;
        } else {
            zc = d;
        }
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
        // When the GPU path is drawing, it OWNS the texture — skip the CPU upload so the coarse CPU
        // render never flashes through while a new GPU mesh rebuilds (the draw keeps showing the
        // previous GPU mesh until the new one lands).
        // gpuReady only means the worker submitted a grid — the mesh may not be uploaded yet (and on
        // first open it never is). Requiring real geometry keeps the CPU render on screen until the
        // GPU can actually take over, instead of blanking the view to an empty texture.
        // Create the GL context BEFORE testing hasGeometry(): the worker cannot stage a mesh without
        // one, so gating context creation behind gpuActive would stop the GPU path ever starting.
        OrbitGpuRenderer.ensureContext();
        boolean gpuActive = MapNative.available() && gpuReady && texture != null && texSize > 16
                && com.mia.aperture.client.MiaApertureModClient.mapSettings.orbitTransparency <= 0
                && OrbitGpuRenderer.hasGeometry();
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
                whole, snapSeq,
                // Belongs to the FRAME, not the cloud: changing it changes how the same geometry is
                // drawn, not what was sampled. Putting it in the cloud signature would re-read the
                // world engine to get an identical grid back.
                com.mia.aperture.client.MiaApertureModClient.mapSettings.orbitTransparency,
                // Likewise: with the camera still, nothing else in this hash moves when the mode
                // changes, so the view would keep showing the old one until you happened to orbit.
                com.mia.aperture.state.AbyssMapState.mapRenderMode,
                // Without this the worker sees an unchanged camera, skips the rebuild, and the view
                // sits on terrain from an engine that has since been shut down.
                MapEngineSource.generation());
    }

    // Worker: sample (if the cloud region changed) + rasterize into buf/bufDepth, then publish.
    private static boolean buildFrame(OrbitCamera cam, double zoom, MapSettings.OrbitQuality quality) {
        // One mode drives both views: V on the map switches the 2D slice and this together, so
        // "cave mode" means one thing rather than two that can disagree.
        boolean caves = com.mia.aperture.state.AbyssMapState.mapRenderMode == MapMode.CAVES;
        // See-through needs correct alpha compositing, which needs per-surface depth sorting. Only
        // the cube path can do that: the smooth mesher emits unsorted triangles, and the GPU
        // renderer has no blend pass at all. So this mode deliberately takes the CPU volume path.
        boolean seeThrough = com.mia.aperture.client.MiaApertureModClient.mapSettings.orbitTransparency > 0;
        Minecraft mc = Minecraft.getInstance();
        var engine = MapEngineSource.get();
        if (engine == null || mc.level == null) return false;
        MapColorSource colors = MapCompositor.colorSource();
        if (colors == null) return false;

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
        // GPU path (worker side): build the occupancy grid — the live box OR the whole-Abyss span model
        // — and submit it to the native renderer, which greedy-meshes it OFF the render thread. Stash
        // the camera so the render thread can draw it into the map texture. gpuRender is the toggle;
        // when it (or the native module) is off, gpuReady stays false and the CPU path renders instead.
        gpuReady = false;
        if (MapNative.available() && com.mia.aperture.client.MiaApertureModClient.mapSettings.gpuRender
                && !seeThrough) {
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
                // Whole-Abyss is a single pre-built column model, not a cascade. Drop any shells from
                // the live path or the submit below would draw those stale instead of this.
                gpuShellGrids = null;
                gpuShellSigs = null;
                gpuShellPlan = null;
            } else {
                // The camera sits ~2x extentXZ from the focus at a 70deg FOV, so the visible frustum
                // footprint is ~3x extentXZ. Sample that wider box or the box edges show as hard walls.
                // Quantize to 64-block zoom buckets so scrolling reuses the same mesh (the 3x coverage
                // spans the in-between zooms) — far fewer re-mesh+re-upload steps while scrolling.
                int gpuBase = OrbitLod.baseFor(extentXZ);
                // Vertical ~= horizontal (both ~3x the base = the frustum footprint). Do NOT stack the
                // 3x on top of VERT_UP/DOWN (that gave a ~9x-tall, mostly-empty column that wasted the
                // grid budget and forced a coarse LOD, capping detail).
                int[] gpuVert = MapGeometry.clampVerticalToAbyss(shiftedFocusY,
                        (int) (gpuBase * VERT_UP), (int) (gpuBase * VERT_DOWN), 8);
                int gpuUp = gpuVert[0], gpuDown = gpuVert[1];
                // Grid budget scales with the 3D Quality tier (Potato -> Ultra): finer LOD on beefier
                // machines, coarser+cheaper on weak ones. Bound the LOD by the LARGEST axis (the Abyss
                // band is tall) so the grid stays within budget on ALL axes — otherwise the vertical
                // dimension explodes at fine LOD and the mesh/VBO upload becomes huge.
                // Level + clamped coverage come from OrbitLod so the settings screen can report the
                // same numbers the renderer acts on.
                OrbitLod.Plan gpuPlan = OrbitLod.plan(extentXZ, gpuUp, gpuDown, quality.gpuGrid, GPU_MAX_LVL, quality.maxCells);
                int gpuLvl = gpuPlan.level();
                int gpuExtentXZ = gpuPlan.coverageBlocks();
                // Key on the SNAPPED origin, not the raw focus: sampleGrid floors the focus to the
                // cell lattice, so a sub-cell move yields a byte-identical grid. Hashing the raw
                // focus rebuilt it anyway — 15 wasted rebuilds out of 16 at level 4.
                gsig = OrbitLod.gridSig(shiftedFocusX, shiftedFocusY, focusZ,
                        gpuExtentXZ, gpuUp, gpuDown, gpuLvl) * 31 + MapEngineSource.generation();

                // CASCADE: a fine box around the focus wrapped in coarser, wider ones. A single level
                // over the whole box makes cost cubic in (span/cell), so fine voxels over a wide area
                // are unreachable at any budget; shells make it additive. Innermost first.
                // GPU_MAX_LVL, not ORBIT_MAX_LVL: Voxy stores nothing past level 4, so a level-5 shell
                // samples EMPTY on the live path — the outer shell would silently vanish. L5 outer
                // shells need the LodUpsampler.mipInto synthesis (with its drawable-child predicate,
                // or the 9b519db pin-art holes return); until that lands the ceiling stays at 4.
                // CAVES is a local view by construction, so it gets the innermost shell only. The
                // outer shells are neither carved nor carvable — they are wide and coarse — and
                // drawing uncarved far terrain around a carved centre would show exactly the
                // see-through view this mode exists to avoid. Same reasoning as whole-Abyss above.
                java.util.List<OrbitLod.Shell> plan = OrbitLod.planCascade(
                        extentXZ, gpuUp, gpuDown, GPU_MAX_LVL, quality.maxCells,
                        caves ? 1 : MAX_SHELLS);
                if (gpuShellGrids == null || gpuShellGrids.length != plan.size()) {
                    gpuShellGrids = new VoxelCloud.Grid[plan.size()];
                    gpuShellSigs = new long[plan.size()];
                    java.util.Arrays.fill(gpuShellSigs, Long.MIN_VALUE);
                }
                for (int i = 0; i < plan.size(); i++) {
                    OrbitLod.Shell sh = plan.get(i);
                    // Each shell keys on ITS OWN cell size, so the wide coarse shell is resampled far
                    // less often than the small fine one as the focus moves.
                    long ssig = OrbitLod.shellSig(sh, shiftedFocusX, shiftedFocusY, focusZ);
                    if (caves) ssig = ~ssig;   // or toggling the mode would reuse the uncarved grid
                    ssig = ssig * 31 + MapEngineSource.generation();
                    if (ssig == gpuShellSigs[i] && gpuShellGrids[i] != null) continue;
                    int half = sh.vertBlocks() / 2;
                    gpuShellGrids[i] = VoxelCloud.sampleGrid(engine, colors, shiftedFocusX,
                            shiftedFocusY, focusZ, sh.spanBlocks(), half, half, sh.level(),
                            caves ? CAVE_SLAB_BLOCKS : 0);
                    gpuShellSigs[i] = ssig;
                }
                gpuGridCache = gpuShellGrids.length > 0 ? gpuShellGrids[0] : null;
                gpuGridSig = gsig;
                gpuShellPlan = plan;
            }
            // Worker thread: mesh off the render thread. submit() meshes once per region (and retries
            // until the GL context exists); the render thread only uploads the staged mesh + draws.
            if (gpuShellGrids != null && gpuShellGrids.length > 0) {
                OrbitGpuRenderer.submit(java.util.Arrays.asList(gpuShellGrids), gpuShellSigs);
                gpuFocusX = focusXExact; gpuFocusY = focusYExact; gpuFocusZ = focusZExact;
                gpuYaw = cam.yawDeg; gpuPitch = cam.pitchDeg; gpuDist = cam.distance;
                gpuReady = true;
            } else if (gpuGridCache != null) {
                OrbitGpuRenderer.submit(gpuGridCache, gsig);
                gpuFocusX = focusXExact; gpuFocusY = focusYExact; gpuFocusZ = focusZExact;
                gpuYaw = cam.yawDeg; gpuPitch = cam.pitchDeg; gpuDist = cam.distance;
                gpuReady = true;
            }
        }
        boolean smooth = com.mia.aperture.client.MiaApertureModClient.mapSettings.smooth3d && !seeThrough;
        long cs = whole
                ? Objects.hash(0x5EAB, AbyssSpanStore.current().seq(), quality.maxPoints)
                // Same snapping as the GPU grid above — VoxelCloud.sample floors the focus to the
                // cell lattice identically, so the CPU cloud is unchanged within a cell too.
                : OrbitLod.gridSig(shiftedFocusX, shiftedFocusY, focusZ, extentXZ, extentUp, extentDown, lvl);
        if (caves) cs = ~cs;    // same reason as the shell signature: the carve changes the grid
        cs = cs * 31 + MapEngineSource.generation();   // a new engine invalidates the cached cloud
        if (cloud == null || cs != cloudSig || whole != cloudWhole || smooth != cloudSmooth) {
            if (whole) {
                // Whole-Abyss reads the span model, not a dense grid, so it stays on the cube
                // renderer this pass (smooth-mesh assembly from spans is a scoped follow-up).
                AbyssModelBuilder.ensureStarted();
                cloud = buildWholeCloud(quality.maxPoints);
                mesh = null;
            } else if (smooth && lvl >= SMOOTH_MIN_LVL) {
                VoxelCloud.Grid grid = VoxelCloud.sampleGrid(engine, colors, shiftedFocusX, shiftedFocusY,
                        focusZ, extentXZ, extentUp, extentDown, lvl, caves ? CAVE_SLAB_BLOCKS : 0);
                mesh = OrbitMesher.build(grid.opaque(), grid.argb(), grid.gX(), grid.gY(), grid.gZ(),
                        grid.cell(), grid.originCellX(), grid.originCellY(), grid.originCellZ());
                cloud = List.of();
            } else {
                cloud = VoxelCloud.sample(engine, colors, shiftedFocusX, shiftedFocusY, focusZ,
                        extentXZ, extentUp, extentDown, lvl, quality.maxPoints,
                        caves ? CAVE_SLAB_BLOCKS : 0);
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
        int strength = com.mia.aperture.client.MiaApertureModClient.mapSettings.orbitTransparency;
        if (strength <= 0) {
            for (VoxelCloud.Point p : pts) drawCube(img, depth, sz, cel, b, focal, p, 1.0f, false);
            return;
        }
        // Back to front. Alpha compositing is order-dependent, and drawing in grid order gives a
        // muddle in which a far wall can wash over a near one. Sorted once per rebuilt frame, on
        // the worker, so the render thread pays nothing for it.
        float alpha = seeThroughAlpha(strength);
        pts.sort((p1, p2) -> Double.compare(dist2(p2, cel), dist2(p1, cel)));
        for (VoxelCloud.Point p : pts) drawCube(img, depth, sz, cel, b, focal, p, alpha, true);
    }

    /**
     * Per-surface alpha for a see-through strength (0-100). Floored well above zero: many layers of
     * rock composite, so an alpha that reaches 0 makes near and far terrain alike vanish instead of
     * accumulating into something you can read depth from.
     */
    static float seeThroughAlpha(int strength) {
        return Math.max(0.08f, 1.0f - Math.max(0, Math.min(100, strength)) / 100.0f);
    }

    private static double dist2(VoxelCloud.Point p, double[] cel) {
        double dx = p.x() - cel[0], dy = p.y() - cel[1], dz = p.z() - cel[2];
        return dx * dx + dy * dy + dz * dz;
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
            fillTri(img, depth, sz, sx[0], sy[0], sx[1], sy[1], sx[2], sy[2], z, color, 1.0f, false);
        }
    }

    private static void drawCube(NativeImage img, float[] depth, int sz,
                                 double[] cel, double[] b, double focal, VoxelCloud.Point p,
                                 float alpha, boolean seeThrough) {
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
            fillTri(img, depth, sz, sx[0], sy[0], sx[1], sy[1], sx[2], sy[2], z, col, alpha, seeThrough);
            fillTri(img, depth, sz, sx[0], sy[0], sx[2], sy[2], sx[3], sy[3], z, col, alpha, seeThrough);
        }
    }

    // Flat-shaded, flat-depth triangle fill with z-buffer (barycentric, both windings).
    private static void fillTri(NativeImage img, float[] depth, int sz,
                                double x0, double y0, double x1, double y1,
                                double x2, double y2, float z, int color, float alpha,
                                boolean seeThrough) {
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
                if (seeThrough) {
                    // Blend everything, near and far, and keep only the NEAREST depth. Rejecting on
                    // depth is what makes a solid render solid, so the see-through pass cannot use
                    // it — but the depth buffer still has to end up holding the closest surface, or
                    // HUD markers lose their occlusion and float over terrain that is in front.
                    if (z < depth[di]) depth[di] = z;
                } else {
                    if (z >= depth[di]) continue;
                    depth[di] = z;
                }
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
