package com.mia.aperture.lod;

import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Turns a live chunk section into the flat id array the store takes.
 */
public final class SectionCapture {
    private SectionCapture() {}

    /**
     * Cell index within a section. <b>Must match the store's own ordering</b> — a mismatch would not
     * fail, it would silently transpose terrain, which is far harder to notice than a crash.
     */
    public static int index(int x, int y, int z) {
        return (y * LodNative.EDGE + z) * LodNative.EDGE + x;
    }

    /**
     * Read a section into {@code out} ({@link LodNative#CELLS} long).
     *
     * @return false if the section is entirely air, in which case {@code out} is left untouched and
     *         the caller should skip it — the store already represents "never seen" and "seen and
     *         empty" distinctly, and writing an air section per empty section of the sky would be a
     *         lot of writes for no information.
     */
    public static boolean capture(LevelChunkSection section, BlockIdCache cache,
                                  int[] out, int[] biomesOut) {
        if (section == null || out.length != LodNative.CELLS) return false;
        if (section.hasOnlyAir()) return false;

        // Biomes at 4x4x4, matching how Minecraft stores them. Without these the map cannot tint
        // grass, foliage or water, which is most of what is visible on the surface.
        if (biomesOut != null && biomesOut.length == LodNative.BIOME_CELLS) {
            for (int by = 0; by < LodNative.BIOME_EDGE; by++) {
                for (int bz = 0; bz < LodNative.BIOME_EDGE; bz++) {
                    for (int bx = 0; bx < LodNative.BIOME_EDGE; bx++) {
                        biomesOut[(by * LodNative.BIOME_EDGE + bz) * LodNative.BIOME_EDGE + bx] =
                                cache.idFor(section.getNoiseBiome(bx, by, bz));
                    }
                }
            }
        }

        for (int y = 0; y < LodNative.EDGE; y++) {
            for (int z = 0; z < LodNative.EDGE; z++) {
                for (int x = 0; x < LodNative.EDGE; x++) {
                    out[index(x, y, z)] = cache.idFor(section.getBlockState(x, y, z));
                }
            }
        }
        return true;
    }
}
