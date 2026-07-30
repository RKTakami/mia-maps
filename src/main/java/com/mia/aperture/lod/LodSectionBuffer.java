package com.mia.aperture.lod;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One meshed section, uploaded to the GPU once and drawn from there every frame.
 *
 * <p>The immediate-mode version re-sent every vertex of every visible section on every frame through
 * a {@code VertexConsumer}. That is fine at the range it had — four sections each way — and it is
 * exactly what stops the range growing: per-frame CPU cost scales with visible surface, so extending
 * the view means paying for it sixty times a second. Uploaded once, a section costs one buffer bind
 * and one draw call per frame regardless of how much geometry it holds.
 *
 * <p><b>Positions are relative to the section's own origin</b>, not to the world and not to the
 * camera. Camera-relative cannot be baked, since the camera moves; world-absolute would put the
 * Abyss's X coordinates — approaching 100,000 — into a 24-bit float mantissa and leave about a
 * hundredth of a block of precision to describe geometry with. A per-section origin keeps every
 * coordinate inside 32 blocks, and the translation to camera space is a uniform written per draw.
 *
 * <p>Format is POSITION_COLOR, matching {@code RenderPipelines.DEBUG_QUADS} — the same pipeline the
 * immediate-mode path already drew through, so this changes how the geometry gets to the GPU and not
 * what it looks like when it arrives.
 */
public final class LodSectionBuffer implements AutoCloseable {
    /** 3 floats of position and 4 bytes of colour. */
    private static final int VERTEX_BYTES = 3 * 4 + 4;

    private final GpuBuffer buffer;
    private final int quads;
    /** Section origin in world blocks; the per-draw translation is built from this. */
    public final float originX, originY, originZ;

    private LodSectionBuffer(GpuBuffer buffer, int quads, float ox, float oy, float oz) {
        this.buffer = buffer;
        this.quads = quads;
        this.originX = ox;
        this.originY = oy;
        this.originZ = oz;
    }

    public GpuBuffer buffer() { return buffer; }
    public int quads() { return quads; }
    public int indexCount() { return quads * 6; }

    /**
     * Upload a mesh. Must be called on the render thread — buffer creation is a GPU call.
     *
     * @return null if the mesh is empty, which is the common case for a section of enclosed rock and
     *         must not cost a zero-length buffer
     */
    public static LodSectionBuffer upload(LodSectionMesh.Mesh mesh, float ox, float oy, float oz,
                                          float lx, float ly, float lz, float ambient) {
        int quads = mesh.quads();
        if (quads == 0) return null;
        float[] p = mesh.positions(), n = mesh.normals();
        int[] col = mesh.colors();

        ByteBuffer bb = ByteBuffer.allocateDirect(quads * 4 * VERTEX_BYTES).order(ByteOrder.nativeOrder());
        for (int q = 0; q < quads; q++) {
            // Light is baked into vertex colour here rather than each frame: POSITION_COLOR carries
            // no normal, so there is nothing for a shader to light, and the normal is not needed
            // again once the shade is folded in.
            float ndotl = Math.max(0f, n[q * 3] * lx + n[q * 3 + 1] * ly + n[q * 3 + 2] * lz);
            float light = ambient + (1f - ambient) * ndotl;
            int c = com.mia.aperture.map.ColorMath.shade(col[q], light) | 0xFF000000;
            // The pipeline reads colour as four unsigned bytes in RGBA order, while the colour is
            // held as ARGB. Writing the int raw would swap the red and blue channels — a mistake
            // that renders perfectly and looks like a palette bug.
            byte cr = (byte) ((c >> 16) & 0xFF), cg = (byte) ((c >> 8) & 0xFF);
            byte cb = (byte) (c & 0xFF), ca = (byte) ((c >>> 24) & 0xFF);
            for (int v = 0; v < 4; v++) {
                int b = (q * 4 + v) * 3;
                bb.putFloat(p[b] - ox).putFloat(p[b + 1] - oy).putFloat(p[b + 2] - oz);
                bb.put(cr).put(cg).put(cb).put(ca);
            }
        }
        bb.flip();

        GpuBuffer buf = RenderSystem.getDevice().createBuffer(
                () -> "mia-loddy section", GpuBuffer.USAGE_VERTEX, bb);
        return new LodSectionBuffer(buf, quads, ox, oy, oz);
    }

    @Override
    public void close() {
        buffer.close();
    }
}
