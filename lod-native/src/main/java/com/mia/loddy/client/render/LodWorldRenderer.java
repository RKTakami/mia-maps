package com.mia.loddy.client.render;

import com.mia.loddy.api.LodService;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-Performance Minecraft-Integrated distance renderer for mia-loddy.
 * Features:
 *  1. Far-distance rendering out to 3,840 blocks (240 chunks) covering all 4 LOD cascade rings.
 *  2. Frustum culling via net.minecraft.client.renderer.culling.Frustum to skip off-screen tiles.
 *  3. Async background thread-pool meshing (MESHER_POOL) to eliminate render thread stuttering.
 *  4. Throttled cascade planning and Minecraft VertexConsumer integration for buttery smooth 60+ FPS.
 */
public final class LodWorldRenderer {
    private static final int MAX_TILES = 8192;
    private static final int[] CASCADE_SCRATCH = new int[MAX_TILES * 4];

    private static volatile boolean disabled = false;
    private static final Map<Long, SectionMesh> MESH_CACHE = new ConcurrentHashMap<>();
    private static final Set<Long> IN_PROGRESS_MESHES = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<MeshResult> UPLOAD_QUEUE = new ConcurrentLinkedQueue<>();

    private static final net.minecraft.resources.Identifier WHITE =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("lod_native", "textures/white.png");

    private static double lastCamX = 1e9, lastCamY = 1e9, lastCamZ = 1e9;
    private static long lastPlanTime = 0;
    private static int cachedTileCount = 0;

    private static final ExecutorService MESHER_POOL = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "MIA-Loddy-Mesher");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
    );

    private LodWorldRenderer() {}

    public static void render(WorldRenderContext context) {
        if (disabled || !LodService.getInstance().isWorldRenderingEnabled()) return;
        long handle = LodService.getInstance().getStoreHandle();
        if (handle == 0) return;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.gameRenderer.getMainCamera() == null) return;

            // 1. Drain up to 32 ready meshes from background meshing queue
            int uploadsThisFrame = 0;
            while (uploadsThisFrame < 32 && !UPLOAD_QUEUE.isEmpty()) {
                MeshResult res = UPLOAD_QUEUE.poll();
                if (res != null) {
                    SectionMesh mesh = new SectionMesh(res.vCount, res.iCount, res.vertices, res.indices);
                    MESH_CACHE.put(res.key, mesh);
                    uploadsThisFrame++;
                }
            }

            Vec3 cam = mc.gameRenderer.getMainCamera().position();
            float camX = (float) cam.x;
            float camY = (float) cam.y;
            float camZ = (float) cam.z;

            int minY = mc.level.getMinY();
            int maxY = mc.level.getMaxY();

            // 2. Far view distance out to 3840 blocks (240 chunks) covering all cascade rings
            float viewDist = Math.max(3840.0f, mc.options.getEffectiveRenderDistance() * 16.0f * 12.0f);

            double distSq = (camX - lastCamX) * (camX - lastCamX)
                          + (camY - lastCamY) * (camY - lastCamY)
                          + (camZ - lastCamZ) * (camZ - lastCamZ);
            long now = System.currentTimeMillis();
            if (distSq > 16.0 || now - lastPlanTime > 500 || cachedTileCount <= 0) {
                lastCamX = camX;
                lastCamY = camY;
                lastCamZ = camZ;
                lastPlanTime = now;
                cachedTileCount = LodService.getInstance().planCascade(camX, camY, camZ, viewDist, minY, maxY, CASCADE_SCRATCH);
            }
            int tileCount = cachedTileCount;
            if (tileCount <= 0) return;

            // 3. Setup Frustum Culling
            Matrix4f proj = mc.gameRenderer.getProjectionMatrix((float) mc.options.fov().get().intValue());
            var frustum = new net.minecraft.client.renderer.culling.Frustum(
                    new Matrix4f(context.matrices().last().pose()), proj);
            frustum.prepare(camX, camY, camZ);

            var vc = context.consumers().getBuffer(RenderTypes.entitySolid(WHITE));
            var pose = context.matrices().last();

            int drawn = 0;
            int totalQuads = 0;
            for (int i = 0; i < tileCount && i < MAX_TILES; i++) {
                int base = i * 4;
                int level = CASCADE_SCRATCH[base];
                int x = CASCADE_SCRATCH[base + 1];
                int y = CASCADE_SCRATCH[base + 2];
                int z = CASCADE_SCRATCH[base + 3];

                int size = 16 * (1 << level);

                // Only skip Level 0 sections inside the server's vanilla chunk distance (clamped to 10 chunks max).
                // Never skip Level 1+ cascade rings or distant terrain!
                int keepSections = Math.min(10, Math.max(0, mc.options.getEffectiveRenderDistance() - 1));
                int camSecX = (int) Math.floor(cam.x / 16.0);
                int camSecZ = (int) Math.floor(cam.z / 16.0);
                if (level == 0 && Math.abs(x - camSecX) <= keepSections && Math.abs(z - camSecZ) <= keepSections) {
                    continue;
                }

                double minX = (double) (x * size);
                double minBoxY = (double) Math.max(minY, -64);
                double minZ = (double) (z * size);
                double maxX = minX + size;
                double maxBoxY = (double) Math.min(maxY, 320);
                double maxZ = minZ + size;

                // Frustum culling: skip tiles outside camera view
                if (!frustum.isVisible(new AABB(minX, minBoxY, minZ, maxX, maxBoxY, maxZ))) {
                    continue;
                }

                long key = tileKey(level, x, y, z);
                SectionMesh mesh = MESH_CACHE.get(key);
                if (mesh == null) {
                    // Async meshing: never stall the Minecraft render thread!
                    if (IN_PROGRESS_MESHES.add(key)) {
                        final int fLevel = level;
                        final int fX = x;
                        final int fY = y;
                        final int fZ = z;
                        MESHER_POOL.execute(() -> {
                            try {
                                int[] vScratch = new int[65536 * 8];
                                int[] iScratch = new int[65536 * 6];
                                long res = LodService.getInstance().meshSection(fLevel, fX, fY, fZ, vScratch, iScratch);
                                int vCount = (int) (res >> 32);
                                int iCount = (int) res;
                                if (vCount > 0 && iCount > 0) {
                                    int[] vCopy = new int[vCount * 8];
                                    int[] iCopy = new int[iCount];
                                    System.arraycopy(vScratch, 0, vCopy, 0, vCopy.length);
                                    System.arraycopy(iScratch, 0, iCopy, 0, iCopy.length);
                                    UPLOAD_QUEUE.add(new MeshResult(key, vCount, iCount, vCopy, iCopy));
                                } else {
                                    MESH_CACHE.put(key, SectionMesh.EMPTY);
                                }
                            } catch (Throwable t) {
                                // Ignore background meshing exception
                            } finally {
                                IN_PROGRESS_MESHES.remove(key);
                            }
                        });
                    }
                    continue;
                }

                if (mesh != SectionMesh.EMPTY) {
                    double ox = (double) (x * size) - cam.x;
                    double oy = (double) (y * size) - cam.y;
                    double oz = (double) (z * size) - cam.z;
                    int[] vertices = mesh.vertices;
                    int vCount = mesh.vCount;
                    for (int vIndex = 0; vIndex < vCount; vIndex++) {
                        int idx = vIndex * 8;
                        float vx = Float.intBitsToFloat(vertices[idx]) + (float) ox;
                        float vy = Float.intBitsToFloat(vertices[idx + 1]) + (float) oy;
                        float vz = Float.intBitsToFloat(vertices[idx + 2]) + (float) oz;
                        int col = vertices[idx + 3];
                        int normInt = vertices[idx + 7];
                        float nx = (float) ((byte) (normInt & 0xFF));
                        float ny = (float) ((byte) ((normInt >> 8) & 0xFF));
                        float nz = (float) ((byte) ((normInt >> 16) & 0xFF));

                        vc.addVertex(pose, vx, vy, vz)
                          .setColor(col)
                          .setUv(0.5f, 0.5f)
                          .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                          .setLight(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT)
                          .setNormal(pose, nx, ny, nz);
                    }
                    drawn++;
                    totalQuads += (mesh.iCount / 6);
                }
            }

            LodService.getInstance().setStats(drawn, totalQuads);

        } catch (Throwable t) {
            System.err.println("[MIA Loddy] World renderer frame error: " + t);
        }
    }

    public static synchronized void invalidateAll() {
        MESH_CACHE.clear();
        UPLOAD_QUEUE.clear();
        IN_PROGRESS_MESHES.clear();
        cachedTileCount = 0;
    }

    private static long tileKey(int level, int x, int y, int z) {
        return ((long) level << 60)
             | ((long) (x + (1 << 19)) << 40)
             | ((long) (y + (1 << 19)) << 20)
             | (z + (1 << 19));
    }

    private static class SectionMesh {
        static final SectionMesh EMPTY = new SectionMesh(0, 0, null, null);
        final int vCount, iCount;
        final int[] vertices;
        final int[] indices;

        SectionMesh(int vCount, int iCount, int[] vertices, int[] indices) {
            this.vCount = vCount;
            this.iCount = iCount;
            this.vertices = vertices;
            this.indices = indices;
        }
    }

    private static class MeshResult {
        final long key;
        final int vCount, iCount;
        final int[] vertices;
        final int[] indices;

        MeshResult(long key, int vCount, int iCount, int[] vertices, int[] indices) {
            this.key = key;
            this.vCount = vCount;
            this.iCount = iCount;
            this.vertices = vertices;
            this.indices = indices;
        }
    }
}
