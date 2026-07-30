package com.mia.aperture.lod;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * One coloured box, drawn in the world at a fixed distance, to answer the only question that can
 * invalidate LOD distance rendering before any of it is built.
 *
 * <p>Nothing in this mod has ever drawn into the world — the map and orbit view both render to their
 * own textures. So before building a mesh pipeline, a store reader and a cascade on the assumption
 * that geometry can be composited into Minecraft's frame, draw <b>one box</b> and check three things:
 *
 * <ol>
 *   <li>it appears at all, so the hook and the camera transform are right;
 *   <li>near terrain <b>occludes</b> it, so we are sharing Minecraft's depth buffer rather than
 *       painting over the frame;
 *   <li>it survives <b>Sodium and Iris</b>, which replace the terrain renderer and the shader
 *       pipeline respectively and are the realistic environment this has to work in.
 * </ol>
 *
 * <p>The third is the one that could sink the stage, and it cannot be answered by reading code.
 *
 * <p>Hooked at AFTER_ENTITIES: terrain and entities are already drawn, so the depth buffer is
 * populated and an occlusion failure is visible rather than masked by draw order.
 *
 * <p>Off unless {@code lodDistanceProbe} is set. This draws into the game view, so it must never be
 * something a player gets by accident.
 */
public final class DistanceProbe {
    private DistanceProbe() {}

    /** The actual subject: far enough that terrain will usually be in the way. */
    private static final double FAR_DISTANCE = 200.0;
    private static final float FAR_HALF = 8.0f;
    private static final int FAR_COLOR = 0xFFFF00FF;    // magenta

    /**
     * A positive control, close enough to be in open air. Without it "no box" is ambiguous between a
     * rendering bug and correct occlusion — and underground, 200 blocks north is usually solid rock,
     * so the honest result and the broken one look identical. If cyan draws and magenta does not,
     * that is occlusion working, which is the answer we want.
     */
    private static final double NEAR_DISTANCE = 12.0;
    private static final float NEAR_HALF = 1.5f;
    private static final int NEAR_COLOR = 0xFF00FFFF;   // cyan

    private static boolean wasEnabled;
    private static boolean logPlacement;

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(DistanceProbe::draw);
        // Say the hook is wired, at startup, unconditionally. "Nothing appeared" has at least three
        // causes — setting off, hook not registered, geometry drawn somewhere wrong — and the first
        // time this was tested the log could not tell them apart, because a disabled probe and a
        // broken one were both silent.
        System.out.println("[MIA Mappy] distance probe hook registered"
                + " (Settings -> \"LOD Distance Probe\" to enable)");
    }

    private static void draw(WorldRenderContext ctx) {
        boolean on = com.mia.aperture.client.MiaApertureModClient.mapSettings.lodDistanceProbe;
        if (on != wasEnabled) {
            wasEnabled = on;
            // println, not printf: Minecraft's stdout swallows printf because it never flushes.
            System.out.println("[MIA Mappy] distance probe " + (on ? "ENABLED" : "disabled"));
            if (on) logPlacement = true;
        }
        if (!on) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        // The pose stack is in camera space, so world coordinates have to be offset by the camera
        // position. Getting this wrong puts the box at the camera instead of in the world, which is
        // the most likely way for this to look like "nothing rendered".
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        double px = mc.player.getX(), py = mc.player.getY() + 2.0, pz = mc.player.getZ();
        double bx = px - cam.x;
        double by = py - cam.y;
        double bz = pz - FAR_DISTANCE - cam.z;

        // Report the arithmetic once per enable. If the box is on but invisible, the next question
        // is whether it is being placed where intended, and guessing at that costs another round.
        if (logPlacement) {
            logPlacement = false;
            System.out.println("[MIA Mappy] probe: player=" + mc.player.position()
                    + " camera=" + cam + " boxCameraRelative=(" + bx + "," + by + "," + bz + ")");
        }

        var vc = ctx.consumers().getBuffer(RenderTypes.lines());
        // Minecraft's own shape renderer, rather than emitting vertices by hand. It owns the normal
        // and line-width conventions of RenderTypes.lines(), both of which cost a round each to get
        // wrong here: a missing line width crashed the client, and a constant normal instead of the
        // per-edge direction drew only the four vertical edges.
        box(ctx, vc, bx, by, bz, FAR_HALF, FAR_COLOR);
        box(ctx, vc, px - cam.x, by, pz - NEAR_DISTANCE - cam.z, NEAR_HALF, NEAR_COLOR);
    }

    private static void box(WorldRenderContext ctx, com.mojang.blaze3d.vertex.VertexConsumer vc,
                            double cx, double cy, double cz, float half, int color) {
        ShapeRenderer.renderShape(ctx.matrices(), vc,
                Shapes.create(new AABB(cx - half, cy - half, cz - half,
                        cx + half, cy + half, cz + half)),
                0.0, 0.0, 0.0, color, 4.0f);
        // Wireframe rather than solid: RenderTypes.lines() is depth-tested and its vertex format is
        // stable, so a failure here is a failure of the hook, not of a guess about which debug type
        // exists. NOTE for anyone following: in 1.21.11 RenderType moved to
        // net.minecraft.client.renderer.rendertype and its factories to RenderTypes (plural).
    }
}
