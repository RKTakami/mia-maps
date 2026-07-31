package com.mia.aperture.lod;

import com.mia.aperture.map.BiomeTintResolver;
import com.mia.aperture.map.BlockColorBake;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Draws terrain from the LOD store into the world, meshed a section at a time.
 *
 * <p>This is the sparse path. The orbit view builds a dense grid over its whole view volume, so its
 * cost is cubic in span÷cell and it must choose a coarse level to stay in budget — measured on this
 * world, 4-block cells at 512 blocks across. Here the unit of work is a stored section: sections the
 * store has never seen cost nothing, sections of enclosed rock produce no faces, and a section's mesh
 * is <b>cached</b>, so moving the camera re-derives nothing. Cost tracks visible surface rather than
 * enclosed volume, which is what makes fine cells affordable at range.
 *
 * <p>Meshes are built on a worker, a few per pass, and the view draws whatever is ready. Terrain
 * therefore fills in progressively instead of stalling the frame — the same trade the map's tile
 * worker makes.
 *
 * <p><b>Fails safe.</b> {@code RenderTypes.debugQuads()} is POSITION_COLOR as far as the bytecode
 * shows, but a vertex format mismatch does not draw nothing — it throws when the vertex is finalised
 * and takes the client down, which happened once already with the line-width element. So the whole
 * submission is guarded: the first throw disables this renderer and says why.
 */
public final class LodWorldRenderer {
    private LodWorldRenderer() {}

    /**
     * The cascade: which store level to draw at, by distance from the viewer.
     *
     * <p>Each step coarser is a quarter of the faces, so the outer bands cost almost nothing next to
     * the inner one. This exists for the LAYER STACK rather than for horizontal range — the renderer
     * already out-reaches the captured data horizontally, but there is data for all thirteen Abyss
     * layers, and a layer 480 blocks away has no business being drawn at two-block cells.
     */
    private static final int[] CASCADE_LEVEL = {1, 2, 3, 4};
    /**
     * Outer edge of each band, in blocks. The last is the renderer's whole reach — far enough to
     * reach the rim, which from the deepest layer is eight layers up at 480 blocks each.
     */
    private static final int[] CASCADE_TO = {224, 640, 1792, 6144};

    /**
     * The boundary between band {@code i} and the next, snapped UP to the COARSER band's section
     * size.
     *
     * <p>This is what stops ground falling between the bands. Membership is a box test, and each
     * level has its own grid: a boundary at 224 lies on the 32-block grid but not the 64-block one,
     * so the coarse band would begin at 256 while the fine band ended at 224 — and everything
     * between belongs to neither. Snapping to the coarser size fixes both, because each section size
     * divides the next.
     */
    /** The coarsest section in the cascade. Every band edge is a multiple of this. */
    private static final int ANCHOR = LodNative.EDGE << CASCADE_LEVEL[CASCADE_LEVEL.length - 1];

    /**
     * The outer edge of band {@code i}, snapped up to {@link #ANCHOR}.
     *
     * <p>Snapping every edge to the COARSEST section size is what makes the bands tile. Because each
     * section size divides that one, an edge on the anchor grid falls on a section boundary at every
     * level, so a section is always entirely inside a band or entirely outside it — never split.
     *
     * <p>The previous version snapped only to the next band's size and then measured distance from
     * the CAMERA, which sits at an arbitrary offset inside its own section. Each level's grid is
     * anchored differently, so the boundaries did not line up: ground just past a boundary was too
     * far for the fine band and too near for the coarse one, and nothing drew it. That showed up as
     * scattered holes and as the top of the player's own layer being cut off.
     */
    private static int bandEdge(int i) {
        int to = CASCADE_TO[Math.min(i, CASCADE_TO.length - 1)];
        return ((to + ANCHOR - 1) / ANCHOR) * ANCHOR;
    }

    /** Finest level, for the constants that still describe the near band. */
    private static final int LEVEL = 1;
    /** Meshes built per worker pass, so a big move fills in rather than stalling. */
    /**
     * Meshes built per worker pass. Three was sized for a view of 405 candidate sections; this one
     * has 14,157, and at three a pass the terrain would take minutes to appear.
     */
    private static final int BUILDS_PER_PASS = 24;
    /**
     * Cached meshes. Has to exceed the number of candidate sections in view, or the cache spends its
     * life being emptied: the old limit was 2048 against 405 candidates, which was comfortable, and
     * the same number against 14,157 meant meshes were discarded as fast as they were built. Only 14
     * sections ever survived to be drawn.
     */
    private static final int CACHE_LIMIT = 24576;

    // Light baked into vertex colour: POSITION_COLOR carries no normal, so there is nothing for a
    // shader to light. Same direction and ambient as the orbit view, so the two agree.
    private static final float LX = 0.321f, LY = 0.919f, LZ = 0.230f, AMBIENT = 0.45f;

    private static final Map<Long, LodSectionMesh.Mesh> CACHE = new ConcurrentHashMap<>();
    private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();
    private static final LinkedBlockingDeque<Long> QUEUE = new LinkedBlockingDeque<>();

    private static volatile LodColorSource colors;
    private static volatile boolean disabled;
    private static volatile Thread worker;

    /** A 1x1 white texture, so an entity render type carries our vertex colour unmodified. */
    private static final net.minecraft.resources.Identifier WHITE =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("mia_aperture_mod", "textures/white.png");

    public static volatile int statDrawn, statQuads, statCached;
    /**
     * Say what the renderer thinks it did, once, the first time it believes it drew something.
     *
     * <p>Without this, "nothing appears" has at least three causes that look identical from outside:
     * no sections meshed, sections meshed but nothing uploaded, or geometry submitted and landing
     * somewhere invisible. Those need different fixes, and the last time this file was worked on the
     * absence of a signal cost a whole round of guessing.
     */
    private static long lastReport;
    private static int lastDrawn = -1;

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(LodWorldRenderer::draw);
        System.out.println("[MIA Mappy] LOD world renderer registered"
                + " (Settings -> \"LOD World Render\" to enable)");
    }

    /** Section key. Biased so negative coordinates pack without colliding. */
    /**
     * Cache key. The LEVEL is part of it, because the cascade holds the same ground at several
     * detail levels at once and a position-only key would have them overwrite one another —
     * silently, since a coarse mesh at a fine section's key still draws something plausible.
     *
     * <p>3 bits of level and 20 of each biased coordinate: 63 bits, and 20 bits spans a million
     * sections, which is 33 million blocks at the finest level in use.
     */
    private static long key(int level, int x, int y, int z) {
        return ((long) level << 60)
                | ((long) (x + (1 << 19)) << 40)
                | ((long) (y + (1 << 19)) << 20)
                | (z + (1 << 19));
    }

    private static int keyLevel(long k) { return (int) (k >>> 60); }
    private static int keyX(long k) { return (int) ((k >>> 40) & 0xFFFFF) - (1 << 19); }
    private static int keyY(long k) { return (int) ((k >>> 20) & 0xFFFFF) - (1 << 19); }
    private static int keyZ(long k) { return (int) (k & 0xFFFFF) - (1 << 19); }

    /** Blocks spanned by one section at this level. */
    private static int sectionBlocks(int level) { return LodNative.EDGE << level; }

    private static void draw(WorldRenderContext ctx) {
        if (disabled) return;
        if (!com.mia.aperture.client.MiaApertureModClient.mapSettings.lodWorldRender) return;
        long handle = LodIndexer.handle();
        if (handle == 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        ensureWorker();

        try {
            Vec3 cam = mc.gameRenderer.getMainCamera().position();
            centreBlockX = mc.player.getX();
            centreBlockY = mc.player.getY();
            centreBlockZ = mc.player.getZ();
            // An entity render type over a 1x1 white texture, because the debug quad types are
            // BROKEN IN VANILLA and no amount of choosing between them helps. DEBUG_FILLED_SNIPPET
            // sets shaders and a blend function but never a vertex format or draw mode, leaving that
            // to each concrete pipeline — and debug_triangle_fan duly calls withVertexFormat while
            // debug_quads and debug_filled_box both do not. Two incomplete pipelines, which is why
            // nothing in the game references either and why neither drew anything here.
            //
            // Entity types are complete and self-evidently render. The cost is a fuller vertex:
            // NEW_ENTITY wants uv, overlay, light and normal as well as position and colour.
            // Cull to what is actually on screen before submitting anything. Every section in the
            // box was being sent every frame regardless of where the camera pointed, and at this
            // range that is roughly four times the geometry the view can possibly contain — the
            // single biggest saving available without changing how the terrain looks.
            org.joml.Matrix4f proj = mc.gameRenderer.getProjectionMatrix(
                    (float) mc.options.fov().get().intValue());
            var frustum = new net.minecraft.client.renderer.culling.Frustum(
                    new org.joml.Matrix4f(ctx.matrices().last().pose()), proj);
            frustum.prepare(cam.x, cam.y, cam.z);

            var vc = ctx.consumers().getBuffer(RenderTypes.entitySolid(WHITE));
            // A control, drawn through the SAME render type and the same vertex path as the terrain,
            // at a distance the terrain occupies. Three hypotheses for the terrain being invisible
            // have now been checked and disproved — draw timing, far-plane clipping, and fog — and
            // the next one would be a fourth guess. This separates the two things that remain:
            // if this cube is visible and the terrain is not, the render type works at distance and
            // the fault is in the meshed geometry; if neither shows, it is the render type or the
            // stage it is submitted at, and nothing about the mesher is worth examining.
            var pose = ctx.matrices().last();
            // Behind the debug switch now, not drawn for everyone. Kept rather than deleted: these
            // two boxes are what finally separated "the geometry never arrives" from "the render
            // type never draws", after three rounds of narrowing by hypothesis got nowhere. The
            // next time terrain vanishes, which box is visible answers in one screenshot.
            boolean controls =
                    com.mia.aperture.client.MiaApertureModClient.mapSettings.lodDistanceProbe;
            if (controls) controlCube(vc, pose, cam, mc);
            // The A/B. RenderTypes.lines() is PROVEN to draw from this hook — DistanceProbe uses it
            // and its boxes were seen. debugQuads() is not proven: nothing in vanilla references it
            // anywhere, so it may simply never be flushed. Drawing the same box both ways, side by
            // side, at the same distance, settles which without another round of reading bytecode.
            if (controls) controlWireframe(ctx, cam, mc);
            int drawn = 0, quads = 0, skippedLoaded = 0, culled = 0;
            // How close the nearest drawn section is. The screenshot cannot distinguish "distant
            // terrain at the wrong colour" from "near terrain at the wrong scale" — both fill the
            // lower view with big flat blocks — but one number does.
            double nearest = Double.MAX_VALUE, farthest = 0;
            int nearX = 0, nearY = 0, nearZ = 0;
            // Sections in view the store has never seen. If terrain looks incomplete this is the
            // number that says whether the renderer is at fault or the world simply has not been
            // walked there — and until now both showed up as an empty mesh.
            int missing = 0;
            // Where each layer's geometry actually ends up, in drawn Y. The stack should overlap by
            // 32 blocks — the layers are 512 tall and are placed 480 apart — so a visible gap means
            // this model is wrong somewhere, and only the real extents can say where.
            java.util.Map<Integer, int[]> layerYs = new java.util.TreeMap<>();
            // Per layer: drawn, culled, never-stored. A layer that contributes nothing has either
            // been looked away from or has no terrain above you, and those need opposite responses
            // — more range versus more exploring. The combined totals cannot separate them.
            java.util.Map<Integer, int[]> layerStats = new java.util.TreeMap<>();
            int span = com.mia.aperture.client.MiaApertureModClient.mapSettings.lodLayerSpan;
            int bandFrom = 0;
            for (int band = 0; band < CASCADE_LEVEL.length; band++) {
              int lvl = CASCADE_LEVEL[band];
              int sb = sectionBlocks(lvl);
              int bandTo = bandEdge(band);
              int reach = (bandTo + sb - 1) / sb;                 // sections needed to span the band
              // Boxes are centred on the player snapped to the anchor grid, not on the player, so
              // every edge stays on a section boundary at every level however the player moves.
              int ax = Math.floorDiv((int) Math.floor(mc.player.getX()), ANCHOR) * ANCHOR;
              int ay = Math.floorDiv((int) Math.floor(mc.player.getY()), ANCHOR) * ANCHOR;
              int az = Math.floorDiv((int) Math.floor(mc.player.getZ()), ANCHOR) * ANCHOR;
              int bcx = Math.floorDiv((int) Math.floor(mc.player.getX()), sb);
              int bcy = Math.floorDiv((int) Math.floor(mc.player.getY()), sb);
              int bcz = Math.floorDiv((int) Math.floor(mc.player.getZ()), sb);
              int sectorSections = com.mia.aperture.map.MapGeometry.SECTOR_SPAN_X / sb;
              int lo = Math.max(bcy - reach, Math.floorDiv(mc.level.getMinY(), sb));
              int hi = Math.min(bcy + reach, Math.floorDiv(mc.level.getMaxY() - 1, sb));
              int vanillaSecs = (mc.options.getEffectiveRenderDistance() * 16) / sb + 1;

              for (int layer = -span; layer <= span; layer++) {
                int layerCx = bcx + layer * sectorSections;
                for (int syy = lo; syy <= hi; syy++) {
                  for (int dz = -reach; dz <= reach; dz++) {
                    for (int dx = -reach; dx <= reach; dx++) {
                        int sxx = layerCx + dx, szz = bcz + dz;
                        // Only your own layer can collide with vanilla; another layer's chunks are
                        // 16384 blocks away and never loaded.
                        if (layer == 0 && vanillaHas(sxx, szz, bcx, bcz, vanillaSecs)) {
                            skippedLoaded++;
                            continue;
                        }
                        double wx = com.mia.aperture.map.MapGeometry.stackedDrawX(sxx * sb, layer);
                        double wy = com.mia.aperture.map.MapGeometry.stackedDrawY(syy * sb, layer);
                        double wz = szz * sb;
                        // Band membership: entirely inside this band's box, and NOT entirely inside
                        // the previous band's. Both boxes are anchored to the coarsest section size,
                        // and a section is aligned to its own size which divides that — so every
                        // section is wholly in exactly one band, with no ground falling between.
                        if (!inBox(wx, wy, wz, sb, ax, ay, az, bandTo)) continue;
                        if (bandFrom > 0 && inBox(wx, wy, wz, sb, ax, ay, az, bandFrom)) continue;
                        double ddx = wx + sb / 2.0 - cam.x;
                        double ddy = wy + sb / 2.0 - cam.y;
                        double ddz = wz + sb / 2.0 - cam.z;
                        double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                        if (!frustum.isVisible(new net.minecraft.world.phys.AABB(wx, wy, wz,
                                wx + sb, wy + sb, wz + sb))) {
                            culled++;
                            layerStats.computeIfAbsent(layer, q -> new int[3])[1]++;
                            continue;
                        }
                        long k = key(lvl, sxx, syy, szz);
                        LodSectionMesh.Mesh m = CACHE.get(k);
                        if (m == null) {
                            if (PENDING.add(k)) QUEUE.addLast(k);
                            continue;
                        }
                        if (m == LodSectionMesh.MISSING) {
                            missing++;
                            layerStats.computeIfAbsent(layer, q -> new int[3])[2]++;
                            continue;
                        }
                        if (m.isEmpty()) continue;
                        submit(vc, pose, m, cam, layer);
                        int[] ye = layerYs.computeIfAbsent(layer,
                                q -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
                        ye[0] = Math.min(ye[0], (int) wy);
                        ye[1] = Math.max(ye[1], (int) wy + sb);
                        layerStats.computeIfAbsent(layer, q -> new int[3])[0]++;
                        drawn++;
                        quads += m.quads();
                        if (dist > farthest) farthest = dist;
                        if (dist < nearest) {
                            nearest = dist;
                            nearX = (int) wx;
                            nearY = (int) wy;
                            nearZ = (int) wz;
                        }
                    }
                  }
                }
              }
              bandFrom = bandTo;
            }

            statDrawn = drawn;
            statQuads = quads;
            statCached = CACHE.size();
            // Reported every few seconds while it is still growing, not once. The question now is
            // whether terrain fills in, and a single line from the first frame it drew anything
            // cannot answer that — the last run's "14 sections" looked like a rendering failure
            // when it was a cache thrashing itself empty.
            long now = System.currentTimeMillis();
            if (now - lastReport > 5000 && drawn != lastDrawn) {
                lastReport = now;
                lastDrawn = drawn;
                System.out.println("[MIA Mappy] LOD world render: " + drawn + " sections, " + quads
                        + " quads beyond the render distance; " + skippedLoaded + " skipped as"
                        + " vanilla's, " + CACHE.size() + " meshed, " + QUEUE.size() + " queued."
                        + " " + culled + " off-screen, " + missing + " in view but never stored, "
                        + (2 * configuredLayerSpan() + 1) + " layer(s). Drawn from "
                        + (int) nearest + " to " + (int) farthest + " blocks; nearest at ("
                        + nearX + "," + nearY + "," + nearZ + "); vanilla reaches "
                        + (mc.options.getEffectiveRenderDistance() * 16) + " blocks, reach "
                        + CASCADE_TO[CASCADE_TO.length - 1] + ", cascade "
                        + java.util.Arrays.toString(CASCADE_TO) + " at levels "
                        + java.util.Arrays.toString(CASCADE_LEVEL) + ".");
                StringBuilder ly = new StringBuilder();
                for (var e : layerStats.entrySet()) {
                    int[] st = e.getValue();
                    int[] y = layerYs.get(e.getKey());
                    ly.append(" layer ").append(e.getKey()).append(": drawn ").append(st[0])
                      .append(", off-screen ").append(st[1]).append(", never stored ").append(st[2]);
                    if (y != null) ly.append(", Y ").append(y[0]).append("..").append(y[1]);
                    ly.append(';');
                }
                System.out.println("[MIA Mappy] LOD layers —" + (ly.length() == 0 ? " none" : ly)
                        + " camera Y " + (int) cam.y + ", SECTOR_DEPTH "
                        + com.mia.aperture.map.MapGeometry.SECTOR_DEPTH
                        + ", world Y " + mc.level.getMinY() + ".." + (mc.level.getMaxY() - 1));
            }
        } catch (Throwable t) {
            // Disable rather than throw again next frame. A render-path failure repeats every frame,
            // so without this a single bad assumption becomes an unrecoverable crash loop.
            disabled = true;
            System.err.println("[MIA Mappy] LOD world renderer disabled after: " + t);
            t.printStackTrace();
        }
    }

    /** Where the view is centred, in blocks, for the worker to evict against. Render thread writes. */
    private static volatile double centreBlockX, centreBlockY, centreBlockZ;

    /**
     * Drop the meshes furthest from the view instead of emptying the cache.
     *
     * <p>Wholesale clearing is only defensible when the limit is far above what is in view. Once it
     * is not, clearing throws away exactly the meshes about to be drawn and the worker rebuilds them
     * immediately — the cache becomes a treadmill, and the symptom is terrain that never fills in
     * rather than terrain that flickers.
     */
    private static void evictFarMeshes() {
        // In BLOCKS, not sections: a section is 32 blocks at level 1 and 128 at level 3, so a
        // section-count threshold would keep a coarse mesh eight times further out than a fine one
        // and evict the wrong things at every level but the finest.
        double px = centreBlockX, py = centreBlockY, pz = centreBlockZ;
        int keep = CASCADE_TO[CASCADE_TO.length - 1] + 256;
        int span = configuredLayerSpan();
        var it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            long k = it.next().getKey();
            int sb = sectionBlocks(keyLevel(k));
            double bx = keyX(k) * (double) sb, by = keyY(k) * (double) sb, bz = keyZ(k) * (double) sb;
            // Reduce to the viewer's own layer first. Terrain from the layer above genuinely is
            // 16384 blocks away, so measured raw every mesh of it is "far" and the cache would
            // discard each one the instant the worker built it.
            int layer = (int) Math.round((bx - px) / com.mia.aperture.map.MapGeometry.SECTOR_SPAN_X);
            double localX = com.mia.aperture.map.MapGeometry.stackedDrawX((int) bx, layer);
            double localY = com.mia.aperture.map.MapGeometry.stackedDrawY((int) by, layer);
            if (Math.abs(layer) > span || Math.abs(localX - px) > keep
                    || Math.abs(localY - py) > keep || Math.abs(bz - pz) > keep) {
                it.remove();
            }
        }
    }

    /**
     * A 24-block magenta cube, 250 blocks due north of the player at eye height.
     *
     * <p>Deliberately outside the render distance and inside the far plane, so it sits exactly where
     * the missing terrain should be. Built by hand rather than from the mesher, so it shares nothing
     * with the code under suspicion except the render type and the vertex submission.
     */
    private static void controlCube(com.mojang.blaze3d.vertex.VertexConsumer vc,
                                    com.mojang.blaze3d.vertex.PoseStack.Pose pose, Vec3 cam,
                                    Minecraft mc) {
        double bx = mc.player.getX() - cam.x;
        double by = mc.player.getY() + 2 - cam.y;
        double bz = mc.player.getZ() - 250 - cam.z;
        float h = 12f;
        float[][] faces = {
                {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
        };
        int[] cols = {0xFFFF00FF, 0xFFFF55FF, 0xFFAA00AA, 0xFFFFAAFF, 0xFFCC00CC, 0xFFFF00AA};
        for (int f = 0; f < 6; f++) {
            float[] n = faces[f];
            // Two in-plane axes for this face, so the quad is wound consistently.
            float[] u = Math.abs(n[0]) > 0.5f ? new float[]{0, 1, 0} : new float[]{1, 0, 0};
            float[] v = Math.abs(n[2]) > 0.5f ? new float[]{0, 1, 0} : new float[]{0, 0, 1};
            for (int i = 0; i < 4; i++) {
                float su = (i == 0 || i == 3) ? -1 : 1;
                float sv = (i < 2) ? -1 : 1;
                float px = (float) bx + n[0] * h + u[0] * su * h + v[0] * sv * h;
                float py = (float) by + n[1] * h + u[1] * su * h + v[1] * sv * h;
                float pz = (float) bz + n[2] * h + u[2] * su * h + v[2] * sv * h;
                vc.addVertex(pose, px, py, pz)
                        .setColor(cols[f])
                        .setUv(0.5f, 0.5f)
                        .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                        .setLight(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT)
                        .setNormal(pose, n[0], n[1], n[2]);
            }
        }
    }

    /**
     * The same control box, 40 blocks east of the quad one, drawn through the render type known to
     * work from this hook.
     *
     * <p>Kept after it did its job. It proved debugQuads never reaches the screen while this does,
     * and it stays as the control for the render type now in use: if the terrain ever goes missing
     * again, whether this box is still visible says immediately which half of the pipeline broke.
     */
    private static void controlWireframe(WorldRenderContext ctx, Vec3 cam, Minecraft mc) {
        double bx = mc.player.getX() + 40 - cam.x;
        double by = mc.player.getY() + 2 - cam.y;
        double bz = mc.player.getZ() - 250 - cam.z;
        var vc = ctx.consumers().getBuffer(RenderTypes.lines());
        net.minecraft.client.renderer.ShapeRenderer.renderShape(ctx.matrices(), vc,
                net.minecraft.world.phys.shapes.Shapes.create(
                        new net.minecraft.world.phys.AABB(bx - 12, by - 12, bz - 12,
                                bx + 12, by + 12, bz + 12)),
                0.0, 0.0, 0.0, 0xFF00FFFF, 4.0f);
    }

    /** The configured layer span, read defensively — eviction runs on the worker thread. */
    private static int configuredLayerSpan() {
        var st = com.mia.aperture.client.MiaApertureModClient.mapSettings;
        return st == null ? 0 : st.lodLayerSpan;
    }

    /** Whether a whole section lies inside the box of half-extent {@code half} about the anchor. */
    private static boolean inBox(double wx, double wy, double wz, int sb,
                                 int ax, int ay, int az, int half) {
        return wx >= ax - half && wx + sb <= ax + half
                && wy >= ay - half && wy + sb <= ay + half
                && wz >= az - half && wz + sb <= az + half;
    }

    /** Whether vanilla has this column loaded, and is therefore already drawing it. */
    private static boolean vanillaHas(int secX, int secZ, int cx, int cz, int keepSections) {
        // A radius test rather than a chunk lookup. Asking the chunk source per section was 14,157
        // queries every frame for an answer that is, to within a section, just "is this inside the
        // render distance" — and the render distance is one field read.
        return Math.abs(secX - cx) <= keepSections && Math.abs(secZ - cz) <= keepSections;
    }

    /**
     * How much dimmer each layer away from your own is drawn.
     *
     * <p>Terrain from another layer is not somewhere you can walk — it is 16384 blocks away and
     * displaced here so the Abyss reads as one shaft. Drawing it identically would let a ledge two
     * layers down look like somewhere to jump to. Dimming per layer also does the work depth cues
     * normally do, since these are far further away than they appear.
     */
    private static final float LAYER_DIM = 0.55f;

    private static void submit(com.mojang.blaze3d.vertex.VertexConsumer vc,
                               com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                               LodSectionMesh.Mesh m, Vec3 cam, int layer) {
        float dim = 1.0f;
        for (int i = Math.abs(layer); i > 0; i--) dim *= LAYER_DIM;
        double ox = com.mia.aperture.map.MapGeometry.stackedDrawX(0, layer);
        double oy = com.mia.aperture.map.MapGeometry.stackedDrawY(0, layer);
        float[] p = m.positions(), n = m.normals();
        int[] col = m.colors();
        for (int q = 0; q < m.quads(); q++) {
            float ndotl = Math.max(0f, n[q * 3] * LX + n[q * 3 + 1] * LY + n[q * 3 + 2] * LZ);
            float light = AMBIENT + (1f - AMBIENT) * ndotl;
            int c = com.mia.aperture.map.ColorMath.shade(col[q], light * dim) | 0xFF000000;
            for (int v = 0; v < 4; v++) {
                int b = (q * 4 + v) * 3;
                // Full-bright: the shade is already baked into the colour above, and asking for the
                // real lightmap at a section the client has never loaded would return darkness.
                vc.addVertex(pose, (float) (p[b] + ox - cam.x), (float) (p[b + 1] + oy - cam.y),
                                (float) (p[b + 2] - cam.z))
                        .setColor(c)
                        .setUv(0.5f, 0.5f)
                        .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                        .setLight(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT)
                        .setNormal(pose, n[q * 3], n[q * 3 + 1], n[q * 3 + 2]);
            }
        }
    }

    private static synchronized void ensureWorker() {
        Thread w = worker;
        if (w != null && w.isAlive()) return;
        Thread t = new Thread(LodWorldRenderer::run, "MIA-LOD-World-Mesher");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        worker = t;
        t.start();
    }

    private static void run() {
        int[] ids = new int[LodNative.CELLS];
        int[] biomes = new int[LodNative.BIOME_CELLS];
        int[] nIds = new int[LodNative.CELLS];
        int[] nBiomes = new int[LodNative.BIOME_CELLS];
        while (true) {
            try {
                Long k = QUEUE.pollFirst(250, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (k == null) continue;
                for (int i = 0; i < BUILDS_PER_PASS && k != null; i++) {
                    build(k, ids, biomes, nIds, nBiomes);
                    PENDING.remove(k);
                    k = QUEUE.pollFirst();
                }
                if (k != null) QUEUE.addFirst(k);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                System.err.println("[MIA Mappy] LOD world mesh failed: " + t);
            }
        }
    }

    private static void build(long k, int[] ids, int[] biomes, int[] nIds, int[] nBiomes) {
        long handle = LodIndexer.handle();
        if (handle == 0) return;
        int lvl = keyLevel(k);
        int x = keyX(k), y = keyY(k), z = keyZ(k);
        int sb = sectionBlocks(lvl);
        int cell = 1 << lvl;

        // Scaled by the layers in view. The limit exists to bound memory, but if it falls below
        // what is on screen the cache spends its life being emptied and terrain never fills in —
        // which is exactly the failure the single-layer version already shipped once.
        if (CACHE.size() > CACHE_LIMIT * (2L * configuredLayerSpan() + 1)) evictFarMeshes();

        if (!LodNative.nGet(handle, lvl, x, y, z, ids, biomes)) {
            CACHE.put(k, LodSectionMesh.MISSING);         // never seen: remember, do not re-ask
            return;
        }
        LodColorSource c = ensureColors(handle);
        if (c == null) {
            PENDING.remove(k);
            return;                                       // colour not ready; ask again next pass
        }

        // Neighbour lookup, so adjacent sections share a surface instead of each being sealed in its
        // own shell — without it the terrain draws as a grid of separate boxes. Six extra reads per
        // section, on the worker, and only when a mesh is actually built.
        int[] ids2 = ids.clone();
        LodSectionMesh.Neighbours nb = (lx, ly, lz) -> {
            int ox = Math.floorDiv(lx, LodNative.EDGE), oy = Math.floorDiv(ly, LodNative.EDGE);
            int oz = Math.floorDiv(lz, LodNative.EDGE);
            if (!LodNative.nGet(handle, lvl, x + ox, y + oy, z + oz, nIds, nBiomes)) return 0;
            int mx = Math.floorMod(lx, LodNative.EDGE), my = Math.floorMod(ly, LodNative.EDGE);
            int mz = Math.floorMod(lz, LodNative.EDGE);
            int block = nIds[(my * LodNative.EDGE + mz) * LodNative.EDGE + mx];
            if (block == LodNative.AIR) return 0;
            int biome = nBiomes[((my / 4) * LodNative.BIOME_EDGE + (mz / 4)) * LodNative.BIOME_EDGE
                    + (mx / 4)];
            return LodColorSource.mappingId(block, biome);
        };

        LodSectionMesh.Mesh m = LodSectionMesh.build(ids2, biomes, c, cell,
                x * sb, y * sb, z * sb, nb);
        CACHE.put(k, m);
    }

    /**
     * The shared colour source, built once. Baking every stored state costs ~240ms and needs the
     * client's model shaper, so it cannot be done per section and must not be done on a frame.
     */
    private static LodColorSource ensureColors(long handle) {
        LodColorSource c = colors;
        if (c != null) return c;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return null;
        synchronized (LodWorldRenderer.class) {
            if (colors != null) return colors;
            LodBlockTable table = LodIndexer.blockTable();
            BlockColorBake bake = new BlockColorBake();
            bake.update(table.size(), table::stateFor);
            BiomeTintResolver tints = new BiomeTintResolver(id -> {
                String k = LodNative.nBlockKey(handle, id);
                return k != null && k.startsWith(LodNative.BIOME_PREFIX)
                        ? k.substring(LodNative.BIOME_PREFIX.length()) : null;
            }, mc.level);
            colors = new LodColorSource(bake.snapshot(), tints);
            System.out.println("[MIA Mappy] LOD world renderer colour ready ("
                    + table.size() + " states)");
            return colors;
        }
    }

    /** Drop everything on world change: section coordinates mean different places per world. */
    public static void reset() {
        CACHE.clear();
        PENDING.clear();
        QUEUE.clear();
        colors = null;
        disabled = false;
        lastReport = 0;
        lastDrawn = -1;
    }
}
