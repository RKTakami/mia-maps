package com.mia.aperture.map;

// Cached column-span model of the whole Abyss shifted column. Filled by AbyssModelBuilder from
// native LOD-4 Voxy data; read by OrbitScene at the "Whole Abyss" area step. This class is pure
// (no Voxy or Minecraft imports) so the span model stays unit-testable.
public final class AbyssSpanStore {
    // One (cellX, cellZ) column: solid runs sorted ascending by bottom, non-overlapping and
    // non-adjacent (touching runs are merged on insert). colors[i] is the ARGB of spans[i]'s top
    // face — the color that matters seen from above; sides reuse it, which is fine at overview
    // scale. Instances are immutable: every mutation returns a new Column.
    public record Column(int[] spans, int[] colors) {}

    private AbyssSpanStore() {}
}
