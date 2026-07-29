package com.mia.aperture.lod;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

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

    /** Blocks north of the player. Beyond render distance on purpose: if it is only visible when the
     *  terrain in front of it is loaded, that tells us the occlusion is real. */
    private static final double DISTANCE = 200.0;
    private static final float HALF = 8.0f;
    private static final int COLOR = 0xFFFF00FF;   // magenta: in no terrain palette, so unmistakable

    private static boolean announced;

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(DistanceProbe::draw);
    }

    private static void draw(WorldRenderContext ctx) {
        if (!com.mia.aperture.client.MiaApertureModClient.mapSettings.lodDistanceProbe) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        if (!announced) {
            announced = true;
            // println, not printf: Minecraft's stdout swallows printf because it never flushes.
            System.out.println("[MIA Maps] distance probe drawing at " + DISTANCE + " blocks north");
        }

        // The pose stack is in camera space, so world coordinates have to be offset by the camera
        // position. Getting this wrong puts the box at the camera instead of in the world, which is
        // the most likely way for this to look like "nothing rendered".
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        double bx = mc.player.getX() - cam.x;
        double by = mc.player.getY() + 2.0 - cam.y;
        double bz = mc.player.getZ() - DISTANCE - cam.z;

        var pose = ctx.matrices().last();
        var vc = ctx.consumers().getBuffer(RenderTypes.lines());
        // Wireframe rather than solid: RenderTypes.lines() is depth-tested and its vertex format is
        // stable, so a failure here is a failure of the hook, not of a guess about which debug type
        // exists. NOTE for anyone following: in 1.21.11 RenderType moved to
        // net.minecraft.client.renderer.rendertype and its factories to RenderTypes (plural).
        float[][] c = {
            {-HALF, -HALF, -HALF}, {HALF, -HALF, -HALF}, {HALF, HALF, -HALF}, {-HALF, HALF, -HALF},
            {-HALF, -HALF, HALF}, {HALF, -HALF, HALF}, {HALF, HALF, HALF}, {-HALF, HALF, HALF},
        };
        int[][] edges = {
            {0,1},{1,2},{2,3},{3,0}, {4,5},{5,6},{6,7},{7,4}, {0,4},{1,5},{2,6},{3,7},
        };
        for (int[] e : edges) {
            for (int k = 0; k < 2; k++) {
                float[] v = c[e[k]];
                vc.addVertex(pose, (float) bx + v[0], (float) by + v[1], (float) bz + v[2])
                        .setColor(COLOR)
                        .setNormal(0f, 1f, 0f);
            }
        }
    }
}
