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
    /** Sections each way. 4 horizontal at 32 blocks covers ~288 blocks. */
    private static final int RADIUS = 4;
    private static final int V_RADIUS = 2;
    /** Meshes built per worker pass, so a big move fills in rather than stalling. */
    private static final int BUILDS_PER_PASS = 3;
    private static final int CACHE_LIMIT = 2048;

    // Light baked into vertex colour: POSITION_COLOR carries no normal, so there is nothing for a
    // shader to light. Same direction and ambient as the orbit view, so the two agree.
    private static final float LX = 0.321f, LY = 0.919f, LZ = 0.230f, AMBIENT = 0.45f;

    /** Uploaded sections, keyed like the mesh cache. Render thread only. */
    private static final Map<Long, LodSectionBuffer> GPU = new java.util.HashMap<>();
    /** Uploads per frame. A big move must fill in over several frames rather than stall one. */
    private static final int UPLOADS_PER_FRAME = 4;

    private static final Map<Long, LodSectionMesh.Mesh> CACHE = new ConcurrentHashMap<>();
    private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();
    private static final LinkedBlockingDeque<Long> QUEUE = new LinkedBlockingDeque<>();

    private static volatile LodColorSource colors;
    private static volatile boolean disabled;
    private static volatile Thread worker;

    public static volatile int statDrawn, statQuads, statCached;

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

            // Collect what is ready, queue what is not, and upload a few. Kept separate from the
            // draw below so the render pass is opened once and stays open for the whole batch.
            java.util.List<LodSectionBuffer> visible = new java.util.ArrayList<>();
            int uploads = 0;
            for (int dy = -V_RADIUS; dy <= V_RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                        int sxx = cx + dx, syy = cy + dy, szz = cz + dz;
                        long k = key(sxx, syy, szz);
                        LodSectionBuffer gpu = GPU.get(k);
                        if (gpu != null) { visible.add(gpu); continue; }
                        if (GPU.containsKey(k)) continue;      // known empty; null is a real entry
                        LodSectionMesh.Mesh m = CACHE.get(k);
                        if (m == null) {
                            if (PENDING.add(k)) QUEUE.addLast(k);
                            continue;
                        }
                        if (uploads >= UPLOADS_PER_FRAME) continue;
                        uploads++;
                        LodSectionBuffer b = LodSectionBuffer.upload(m,
                                sxx * SECTION_BLOCKS, syy * SECTION_BLOCKS, szz * SECTION_BLOCKS,
                                LX, LY, LZ, AMBIENT);
                        GPU.put(k, b);                          // null means empty: remember it
                        if (b != null) visible.add(b);
                    }
                }
            }

            evictFar(cx, cy, cz);
            statDrawn = visible.size();
            statCached = GPU.size();
            int quads = 0;
            for (LodSectionBuffer b : visible) quads += b.quads();
            statQuads = quads;
            if (!visible.isEmpty()) drawAll(visible, cam);
        } catch (Throwable t) {
            // Disable rather than throw again next frame. A render-path failure repeats every frame,
            // so without this a single bad assumption becomes an unrecoverable crash loop.
            disabled = true;
            System.err.println("[MIA Mappy] LOD world renderer disabled after: " + t);
            t.printStackTrace();
        }
    }

    /**
     * Release sections the player has left behind.
     *
     * <p>The mesh cache is CPU memory and bounded by a crude size limit. These are not: an unevicted
     * GPU buffer is video memory held until the world changes, and walking a few thousand blocks
     * would accumulate every section crossed. Keyed on distance from the current centre rather than
     * on a count, so what is kept is what could plausibly come back into view.
     */
    private static void evictFar(int cx, int cy, int cz) {
        if (GPU.size() <= (RADIUS * 2 + 1) * (RADIUS * 2 + 1) * (V_RADIUS * 2 + 1) * 2) return;
        int keepH = RADIUS + 2, keepV = V_RADIUS + 2;
        var it = GPU.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            long k = e.getKey();
            int x = (int) ((k >>> 42) & 0x1FFFFF) - (1 << 20);
            int y = (int) ((k >>> 21) & 0x1FFFFF) - (1 << 10);
            int z = (int) (k & 0x1FFFFF) - (1 << 20);
            if (Math.abs(x - cx) <= keepH && Math.abs(z - cz) <= keepH && Math.abs(y - cy) <= keepV) {
                continue;
            }
            if (e.getValue() != null) e.getValue().close();
            it.remove();
        }
    }

    /**
     * Draw every visible section in one pass.
     *
     * <p>The pass targets Minecraft's own colour and depth attachments and clears neither, which is
     * what puts this geometry in the world rather than over it: sharing the depth buffer is why real
     * terrain occludes it. Getting that wrong does not look like a bug in this file — it looks like
     * LOD terrain painted on top of the mountain in front of you.
     *
     * <p>Indices come from the shared sequential quad buffer rather than one of our own. Every quad
     * mesh in the game uses the same 0,1,2 / 0,2,3 pattern, so Minecraft keeps exactly one and grows
     * it on demand; allocating a private copy per section would waste memory to duplicate it.
     */
    private static void drawAll(java.util.List<LodSectionBuffer> sections, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();

        int maxIndices = 0;
        for (LodSectionBuffer b : sections) maxIndices = Math.max(maxIndices, b.indexCount());
        var seq = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        var indexBuffer = seq.getBuffer(maxIndices);

        // One transform per section, written in a single batch. The uniform holds the model matrix,
        // so the section's baked-at-origin vertices are moved into camera space here — which is what
        // lets the vertex data itself stay static while the camera moves.
        var uniforms = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms();
        com.mojang.blaze3d.buffers.GpuBufferSlice[] slices =
                new com.mojang.blaze3d.buffers.GpuBufferSlice[sections.size()];
        for (int i = 0; i < sections.size(); i++) {
            LodSectionBuffer b = sections.get(i);
            org.joml.Matrix4f model = new org.joml.Matrix4f().translation(
                    (float) (b.originX - cam.x), (float) (b.originY - cam.y),
                    (float) (b.originZ - cam.z));
            slices[i] = uniforms.writeTransform(model, new org.joml.Vector4f(1, 1, 1, 1),
                    new org.joml.Vector3f(), new org.joml.Matrix4f());
        }

        try (var pass = device.createCommandEncoder().createRenderPass(
                () -> "mia-loddy world", target.getColorTextureView(), java.util.OptionalInt.empty(),
                target.getDepthTextureView(), java.util.OptionalDouble.empty())) {
            pass.setPipeline(net.minecraft.client.renderer.RenderPipelines.DEBUG_QUADS);
            pass.setIndexBuffer(indexBuffer, seq.type());
            for (int i = 0; i < sections.size(); i++) {
                LodSectionBuffer b = sections.get(i);
                pass.setUniform("DynamicTransforms", slices[i]);
                pass.setVertexBuffer(0, b.buffer());
                pass.drawIndexed(0, 0, b.indexCount(), 1);
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

        if (CACHE.size() > CACHE_LIMIT) CACHE.clear();   // crude, but bounded; range is small

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
        // GPU buffers are owned, not garbage: dropping the map without closing them leaks video
        // memory for the rest of the session. Render thread only, hence the queue rather than a
        // direct free — reset is called from wherever a world change is noticed.
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                for (LodSectionBuffer b : GPU.values()) if (b != null) b.close();
                GPU.clear();
            });
        }
        CACHE.clear();
        PENDING.clear();
        QUEUE.clear();
        colors = null;
        disabled = false;
    }
}
