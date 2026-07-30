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

    /** Store level to draw. 1 = 32-block sections, 2-block cells. */
    private static final int LEVEL = 1;
    private static final int SECTION_BLOCKS = LodNative.EDGE << LEVEL;
    private static final int CELL = 1 << LEVEL;
    /**
     * Sections each way. This has to reach PAST vanilla's render distance to show anything at all:
     * inside it, every quad we draw is behind a real chunk and correctly invisible. 16 sections of
     * 32 blocks is +/-512, beyond a 24-chunk view.
     */
    private static final int RADIUS = 16;
    /** +/-192 blocks. The Abyss is vertical, so a two-section slab showed almost nothing. */
    private static final int V_RADIUS = 6;
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
    private static long key(int x, int y, int z) {
        return ((long) (x + (1 << 20)) << 42) | ((long) (y + (1 << 10)) << 21) | (z + (1 << 20));
    }

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
            int cx = Math.floorDiv((int) Math.floor(mc.player.getX()), SECTION_BLOCKS);
            int cy = Math.floorDiv((int) Math.floor(mc.player.getY()), SECTION_BLOCKS);
            int cz = Math.floorDiv((int) Math.floor(mc.player.getZ()), SECTION_BLOCKS);

            centreX = cx; centreY = cy; centreZ = cz;
            // Vanilla's reach in our section units, rounded up so we never draw inside it and
            // z-fight. One chunk of margin, because the edge of the render distance is ragged.
            int vanillaSections = (mc.options.getEffectiveRenderDistance() * 16) / SECTION_BLOCKS + 1;

            // debugFilledBox, not debugQuads. The A/B proved debugQuads never reaches the screen
            // from this hook while lines() does, and comparing the three definitions shows why it is
            // the odd one out: lines and debugFilledBox both set VIEW_OFFSET_Z_LAYERING, debugQuads
            // is the only member of the family that goes straight from sortOnUpload to
            // createRenderSetup with no layering transform at all. Both build on the same
            // DEBUG_FILLED_SNIPPET, so the geometry format is unchanged — this swaps the render
            // setup and nothing else. It also explains why nothing in vanilla references debugQuads.
            var vc = ctx.consumers().getBuffer(RenderTypes.debugFilledBox());
            // A control, drawn through the SAME render type and the same vertex path as the terrain,
            // at a distance the terrain occupies. Three hypotheses for the terrain being invisible
            // have now been checked and disproved — draw timing, far-plane clipping, and fog — and
            // the next one would be a fourth guess. This separates the two things that remain:
            // if this cube is visible and the terrain is not, the render type works at distance and
            // the fault is in the meshed geometry; if neither shows, it is the render type or the
            // stage it is submitted at, and nothing about the mesher is worth examining.
            var pose = ctx.matrices().last();
            controlCube(vc, pose, cam, mc);
            // The A/B. RenderTypes.lines() is PROVEN to draw from this hook — DistanceProbe uses it
            // and its boxes were seen. debugQuads() is not proven: nothing in vanilla references it
            // anywhere, so it may simply never be flushed. Drawing the same box both ways, side by
            // side, at the same distance, settles which without another round of reading bytecode.
            controlWireframe(ctx, cam, mc);
            int drawn = 0, quads = 0, skippedLoaded = 0;
            // How close the nearest drawn section is. The screenshot cannot distinguish "distant
            // terrain at the wrong colour" from "near terrain at the wrong scale" — both fill the
            // lower view with big flat blocks — but one number does.
            double nearest = Double.MAX_VALUE;
            int nearX = 0, nearY = 0, nearZ = 0;
            for (int dy = -V_RADIUS; dy <= V_RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                        int sxx = cx + dx, syy = cy + dy, szz = cz + dz;
                        // Skip anything vanilla is already drawing. Inside the render distance our
                        // geometry is behind real terrain and invisible, so meshing it is pure cost;
                        // worse, where it coincides it would z-fight. This renderer's whole job is
                        // the ground vanilla has given up on.
                        if (vanillaHas(sxx, szz, cx, cz, vanillaSections)) {
                            skippedLoaded++;
                            continue;
                        }
                        long k = key(sxx, syy, szz);
                        LodSectionMesh.Mesh m = CACHE.get(k);
                        if (m == null) {
                            if (PENDING.add(k)) QUEUE.addLast(k);
                            continue;
                        }
                        if (m.isEmpty()) continue;
                        submit(vc, pose, m, cam);
                        drawn++;
                        quads += m.quads();
                        double ddx = sxx * SECTION_BLOCKS + 16 - cam.x;
                        double ddy = syy * SECTION_BLOCKS + 16 - cam.y;
                        double ddz = szz * SECTION_BLOCKS + 16 - cam.z;
                        double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                        if (dist < nearest) {
                            nearest = dist;
                            nearX = sxx * SECTION_BLOCKS;
                            nearY = syy * SECTION_BLOCKS;
                            nearZ = szz * SECTION_BLOCKS;
                        }
                    }
                }
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
                        + " Nearest drawn section " + (int) nearest + " blocks away at ("
                        + nearX + "," + nearY + "," + nearZ + "); vanilla reaches "
                        + (mc.options.getEffectiveRenderDistance() * 16) + " blocks, cell size "
                        + CELL + ".");
            }
        } catch (Throwable t) {
            // Disable rather than throw again next frame. A render-path failure repeats every frame,
            // so without this a single bad assumption becomes an unrecoverable crash loop.
            disabled = true;
            System.err.println("[MIA Mappy] LOD world renderer disabled after: " + t);
            t.printStackTrace();
        }
    }

    /** Where the view is centred, for the worker to evict against. Written by the render thread. */
    private static volatile int centreX, centreY, centreZ;

    /**
     * Drop the meshes furthest from the view instead of emptying the cache.
     *
     * <p>Wholesale clearing is only defensible when the limit is far above what is in view. Once it
     * is not, clearing throws away exactly the meshes about to be drawn and the worker rebuilds them
     * immediately — the cache becomes a treadmill, and the symptom is terrain that never fills in
     * rather than terrain that flickers.
     */
    private static void evictFarMeshes() {
        int cx = centreX, cy = centreY, cz = centreZ;
        var it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            long k = it.next().getKey();
            int x = (int) ((k >>> 42) & 0x1FFFFF) - (1 << 20);
            int y = (int) ((k >>> 21) & 0x1FFFFF) - (1 << 10);
            int z = (int) (k & 0x1FFFFF) - (1 << 20);
            if (Math.abs(x - cx) > RADIUS + 2 || Math.abs(z - cz) > RADIUS + 2
                    || Math.abs(y - cy) > V_RADIUS + 2) {
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
                vc.addVertex(pose, px, py, pz).setColor(cols[f]);
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

    /** Whether vanilla has this column loaded, and is therefore already drawing it. */
    private static boolean vanillaHas(int secX, int secZ, int cx, int cz, int keepSections) {
        // A radius test rather than a chunk lookup. Asking the chunk source per section was 14,157
        // queries every frame for an answer that is, to within a section, just "is this inside the
        // render distance" — and the render distance is one field read.
        return Math.abs(secX - cx) <= keepSections && Math.abs(secZ - cz) <= keepSections;
    }

    private static void submit(com.mojang.blaze3d.vertex.VertexConsumer vc,
                               com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                               LodSectionMesh.Mesh m, Vec3 cam) {
        float[] p = m.positions(), n = m.normals();
        int[] col = m.colors();
        for (int q = 0; q < m.quads(); q++) {
            float ndotl = Math.max(0f, n[q * 3] * LX + n[q * 3 + 1] * LY + n[q * 3 + 2] * LZ);
            float light = AMBIENT + (1f - AMBIENT) * ndotl;
            int c = com.mia.aperture.map.ColorMath.shade(col[q], light) | 0xFF000000;
            for (int v = 0; v < 4; v++) {
                int b = (q * 4 + v) * 3;
                vc.addVertex(pose, (float) (p[b] - cam.x), (float) (p[b + 1] - cam.y),
                        (float) (p[b + 2] - cam.z)).setColor(c);
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
        int x = (int) ((k >>> 42) & 0x1FFFFF) - (1 << 20);
        int y = (int) ((k >>> 21) & 0x1FFFFF) - (1 << 10);
        int z = (int) (k & 0x1FFFFF) - (1 << 20);

        if (CACHE.size() > CACHE_LIMIT) evictFarMeshes();

        if (!LodNative.nGet(handle, LEVEL, x, y, z, ids, biomes)) {
            CACHE.put(k, LodSectionMesh.EMPTY);           // never seen: remember, do not re-ask
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
            if (!LodNative.nGet(handle, LEVEL, x + ox, y + oy, z + oz, nIds, nBiomes)) return 0;
            int mx = Math.floorMod(lx, LodNative.EDGE), my = Math.floorMod(ly, LodNative.EDGE);
            int mz = Math.floorMod(lz, LodNative.EDGE);
            int block = nIds[(my * LodNative.EDGE + mz) * LodNative.EDGE + mx];
            if (block == LodNative.AIR) return 0;
            int biome = nBiomes[((my / 4) * LodNative.BIOME_EDGE + (mz / 4)) * LodNative.BIOME_EDGE
                    + (mx / 4)];
            return LodColorSource.mappingId(block, biome);
        };

        LodSectionMesh.Mesh m = LodSectionMesh.build(ids2, biomes, c, CELL,
                x * SECTION_BLOCKS, y * SECTION_BLOCKS, z * SECTION_BLOCKS, nb);
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
