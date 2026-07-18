package com.mia.aperture.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AbyssSpanStoreTest {

    @Test
    void keyPackingRoundTripsOverTheDomain() {
        for (int x : new int[]{-512, -1, 0, 511}) {
            for (int z : new int[]{-512, -1, 0, 511}) {
                int k = AbyssSpanStore.packKey(x, z);
                assertEquals(x, AbyssSpanStore.keyX(k), "x for " + x + "," + z);
                assertEquals(z, AbyssSpanStore.keyZ(k), "z for " + x + "," + z);
            }
        }
    }

    @Test
    void aLoneCubeExposesAllSixFaces() {
        Map<Integer, AbyssSpanStore.Column> base = new HashMap<>();
        base.put(AbyssSpanStore.packKey(3, 4), SpanMath.insertRun(null, 7, 7, 0xAA));
        List<int[]> cells = collect(base);
        assertEquals(1, cells.size());
        int[] c = cells.get(0);
        assertEquals(3, c[0]);
        assertEquals(7, c[1]);
        assertEquals(4, c[2]);
        assertEquals(0b111111, c[4], "all six faces exposed");
    }

    @Test
    void aTallPillarExposesSidesAndOnlyItsEnds() {
        Map<Integer, AbyssSpanStore.Column> base = new HashMap<>();
        base.put(AbyssSpanStore.packKey(0, 0), SpanMath.insertRun(null, 5, 0, 0xBB));
        List<int[]> cells = collect(base);
        assertEquals(6, cells.size());
        for (int[] c : cells) {
            boolean top = c[1] == 5, bottom = c[1] == 0;
            assertEquals(top, (c[4] & 0b000100) != 0, "+Y only at the top, y=" + c[1]);
            assertEquals(bottom, (c[4] & 0b001000) != 0, "-Y only at the bottom, y=" + c[1]);
            assertTrue((c[4] & 0b000001) != 0, "+X side exposed");
        }
    }

    @Test
    void aBuriedCellEmitsNothing() {
        Map<Integer, AbyssSpanStore.Column> base = new HashMap<>();
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                base.put(AbyssSpanStore.packKey(x, z), SpanMath.insertRun(null, 2, 0, 1));
        for (int[] c : collect(base)) {
            assertFalse(c[0] == 0 && c[1] == 1 && c[2] == 0, "enclosed centre cell must not emit");
        }
        assertEquals(AbyssSpanStore.countSurfaces(base), collect(base).size());
    }

    @Test
    void publishSwapsTheCurrentSnapshotAndBumpsSeq() {
        AbyssSpanStore.Snapshot before = AbyssSpanStore.current();
        Map<Integer, AbyssSpanStore.Column> base = new HashMap<>();
        base.put(AbyssSpanStore.packKey(1, 1), SpanMath.insertRun(null, 3, 1, 5));
        AbyssSpanStore.Snapshot snap = AbyssSpanStore.buildSnapshot(base, 10, 20);
        AbyssSpanStore.publish(snap);
        try {
            assertSame(snap, AbyssSpanStore.current());
            assertTrue(snap.seq() > before.seq());
            assertEquals(10, snap.probedSections());
            assertEquals(20, snap.totalSections());
            assertEquals(3, snap.surfaceCounts().length);
            assertTrue(snap.surfaceCounts()[0] > 0);
        } finally {
            AbyssSpanStore.publish(AbyssSpanStore.buildSnapshot(new HashMap<>(), 0, 0));
        }
    }

    @Test
    void mipLevelsHalveTheColumnDomain() {
        Map<Integer, AbyssSpanStore.Column> base = new HashMap<>();
        base.put(AbyssSpanStore.packKey(4, 6), SpanMath.insertRun(null, 8, 8, 1));
        base.put(AbyssSpanStore.packKey(5, 7), SpanMath.insertRun(null, 9, 9, 2));
        Map<Integer, AbyssSpanStore.Column> mip = AbyssSpanStore.mipMap(base);
        assertEquals(1, mip.size(), "both columns fold into (2,3)");
        AbyssSpanStore.Column m = mip.get(AbyssSpanStore.packKey(2, 3));
        assertNotNull(m);
        assertTrue(SpanMath.solidAt(m, 4));
    }

    private static List<int[]> collect(Map<Integer, AbyssSpanStore.Column> map) {
        List<int[]> out = new ArrayList<>();
        AbyssSpanStore.forEachSurface(map, (x, y, z, color, faces) -> out.add(new int[]{x, y, z, color, faces}));
        return out;
    }
}
