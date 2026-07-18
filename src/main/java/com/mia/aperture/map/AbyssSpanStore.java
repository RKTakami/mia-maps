package com.mia.aperture.map;

import java.util.HashMap;
import java.util.Map;

// Cached column-span model of the whole Abyss shifted column. Filled by AbyssModelBuilder from
// native LOD-4 Voxy data; read by OrbitScene at the "Whole Abyss" area step. This class is pure
// (no Voxy or Minecraft imports) so the span model stays unit-testable.
public final class AbyssSpanStore {
    // One (cellX, cellZ) column: solid runs sorted ascending by bottom, non-overlapping and
    // non-adjacent (touching runs are merged on insert). colors[i] is the ARGB of spans[i]'s top
    // face — the color that matters seen from above; sides reuse it, which is fine at overview
    // scale. Instances are immutable: every mutation returns a new Column.
    public record Column(int[] spans, int[] colors) {}

    // Face bit order matches OrbitScene.FACES: +X, -X, +Y, -Y, +Z, -Z.
    public interface SurfaceVisitor { void cell(int cellX, int cellY, int cellZ, int color, int faces); }

    // Immutable published state: base level (16-block cells) plus two mips (32, 64). Maps must not
    // be mutated after publish — the builder copies its working map into each snapshot.
    public record Snapshot(Map<Integer, Column> base, Map<Integer, Column> mip1, Map<Integer, Column> mip2,
                           int[] surfaceCounts, long seq, long builtAtMs,
                           int probedSections, int totalSections) {
        public Map<Integer, Column> level(int l) {
            return l == 0 ? base : l == 1 ? mip1 : mip2;
        }
    }

    public static final int LEVELS = 3;

    private static long seqCounter;
    private static volatile Snapshot current =
            new Snapshot(Map.of(), Map.of(), Map.of(), new int[LEVELS], 0, 0, 0, 0);

    private AbyssSpanStore() {}

    public static Snapshot current() { return current; }

    public static void publish(Snapshot s) { current = s; }

    public static int cellSize(int level) { return 16 << level; }

    // Column key: 12 bits per axis, biased. Domain here is |cell| <= 2047, comfortably beyond the
    // sector band's [-512, 512) at base level.
    public static int packKey(int cellX, int cellZ) {
        return ((cellX + 2048) << 12) | (cellZ + 2048);
    }

    public static int keyX(int key) { return (key >>> 12) - 2048; }

    public static int keyZ(int key) { return (key & 0xFFF) - 2048; }

    // Derive the next-coarser level: 2x2 columns fold into one, Y halves (SpanMath.mipInto).
    public static Map<Integer, Column> mipMap(Map<Integer, Column> src) {
        Map<Integer, Column> out = new HashMap<>();
        for (Map.Entry<Integer, Column> e : src.entrySet()) {
            int key = packKey(Math.floorDiv(keyX(e.getKey()), 2), Math.floorDiv(keyZ(e.getKey()), 2));
            out.put(key, SpanMath.mipInto(out.get(key), e.getValue()));
        }
        return out;
    }

    // Assemble a snapshot from the builder's working map: copies the map (the builder keeps
    // mutating its own), derives both mips, counts surfaces per level.
    public static Snapshot buildSnapshot(Map<Integer, Column> working, int probed, int total) {
        Map<Integer, Column> base = new HashMap<>(working);
        Map<Integer, Column> mip1 = mipMap(base);
        Map<Integer, Column> mip2 = mipMap(mip1);
        int[] counts = {countSurfaces(base), countSurfaces(mip1), countSurfaces(mip2)};
        synchronized (AbyssSpanStore.class) {
            return new Snapshot(base, mip1, mip2, counts, ++seqCounter,
                    System.currentTimeMillis(), probed, total);
        }
    }

    public static int countSurfaces(Map<Integer, Column> map) {
        int[] n = {0};
        forEachSurface(map, (x, y, z, c, f) -> n[0]++);
        return n[0];
    }

    // Emit every solid cell with at least one of its six neighbours empty. Within a column, merged
    // spans mean "the cell above is solid iff below the span top", so +Y/-Y are just the span ends;
    // the four side faces test the neighbouring columns at the same cellY. Cliff walls come from
    // side exposure — a tall span against an empty neighbour emits its whole height.
    public static void forEachSurface(Map<Integer, Column> map, SurfaceVisitor v) {
        for (Map.Entry<Integer, Column> e : map.entrySet()) {
            int x = keyX(e.getKey()), z = keyZ(e.getKey());
            Column xp = map.get(packKey(x + 1, z));
            Column xm = map.get(packKey(x - 1, z));
            Column zp = map.get(packKey(x, z + 1));
            Column zm = map.get(packKey(x, z - 1));
            Column c = e.getValue();
            for (int i = 0; i < c.spans().length; i++) {
                int top = SpanMath.spanTop(c.spans()[i]);
                int bottom = SpanMath.spanBottom(c.spans()[i]);
                int color = c.colors()[i];
                for (int y = bottom; y <= top; y++) {
                    int faces = 0;
                    if (!SpanMath.solidAt(xp, y)) faces |= 1;
                    if (!SpanMath.solidAt(xm, y)) faces |= 1 << 1;
                    if (y == top) faces |= 1 << 2;
                    if (y == bottom) faces |= 1 << 3;
                    if (!SpanMath.solidAt(zp, y)) faces |= 1 << 4;
                    if (!SpanMath.solidAt(zm, y)) faces |= 1 << 5;
                    if (faces != 0) v.cell(x, y, z, color, faces);
                }
            }
        }
    }
}
