package com.mia.aperture.lod;

import com.mia.aperture.map.MapGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoreTransferTest {

    // A fake packed cell: block in the low 16 bits, biome in the next 8, air when zero. Enough to
    // exercise the split without depending on Voxy's encoding.
    private static long cell(int block, int biome) { return (long) block | ((long) biome << 16); }

    private final StoreTransfer.CellReader reader = new StoreTransfer.CellReader() {
        @Override public boolean isAir(long c) { return c == 0; }
        @Override public int block(long c) { return (int) (c & 0xFFFF); }
        @Override public int biome(long c) { return (int) ((c >>> 16) & 0xFF); }
    };

    private static long[] voxySection() { return new long[32 * 32 * 32]; }
    private static int vIdx(int x, int y, int z) { return (y << 10) | (z << 5) | x; }
    private static int oIdx(int x, int y, int z) { return (y * 16 + z) * 16 + x; }

    @Test
    void eachOctantPicksUpItsOwnEighthAndNothingElse() {
        // The two index conventions differ — Voxy's is (y<<10)|(z<<5)|x, ours is (y*16+z)*16+x — and
        // getting that wrong transposes terrain rather than failing, which is far harder to notice.
        long[] voxy = voxySection();
        voxy[vIdx(0, 0, 0)] = cell(11, 0);         // octant (0,0,0)
        voxy[vIdx(31, 31, 31)] = cell(22, 0);      // octant (1,1,1)
        voxy[vIdx(16, 0, 0)] = cell(33, 0);        // octant (1,0,0)

        int[] cells = new int[LodNative.CELLS];
        int[] biomes = new int[LodNative.BIOME_CELLS];

        assertTrue(StoreTransfer.octantToOurs(voxy, 0, 0, 0, reader, cells, biomes));
        assertEquals(11, cells[oIdx(0, 0, 0)]);
        assertEquals(LodNative.AIR, cells[oIdx(15, 15, 15)], "must not reach into another octant");

        assertTrue(StoreTransfer.octantToOurs(voxy, 1, 1, 1, reader, cells, biomes));
        assertEquals(22, cells[oIdx(15, 15, 15)]);
        assertEquals(LodNative.AIR, cells[oIdx(0, 0, 0)]);

        assertTrue(StoreTransfer.octantToOurs(voxy, 1, 0, 0, reader, cells, biomes));
        assertEquals(33, cells[oIdx(0, 0, 0)], "x octant maps to local x=0");
    }

    @Test
    void anEmptyOctantIsReportedAsNotWorthStoring() {
        long[] voxy = voxySection();
        voxy[vIdx(0, 0, 0)] = cell(11, 0);
        int[] cells = new int[LodNative.CELLS];
        int[] biomes = new int[LodNative.BIOME_CELLS];
        assertFalse(StoreTransfer.octantToOurs(voxy, 1, 1, 1, reader, cells, biomes),
                "an octant with nothing in it should not be written");
    }

    @Test
    void unresolvableBlocksAreDroppedRatherThanStoredAsIdZero() {
        // A block whose state Voxy cannot resolve must not become "air with an id", which would read
        // back as solid nothing and paint a hole in otherwise good terrain.
        long[] voxy = voxySection();
        voxy[vIdx(1, 1, 1)] = cell(0, 5);          // non-air cell whose block resolves to AIR
        int[] cells = new int[LodNative.CELLS];
        int[] biomes = new int[LodNative.BIOME_CELLS];
        StoreTransfer.CellReader unresolvable = new StoreTransfer.CellReader() {
            @Override public boolean isAir(long c) { return false; }
            @Override public int block(long c) { return LodNative.AIR; }
            @Override public int biome(long c) { return 5; }
        };
        assertFalse(StoreTransfer.octantToOurs(voxy, 0, 0, 0, unresolvable, cells, biomes));
        assertEquals(LodNative.AIR, cells[oIdx(1, 1, 1)]);
    }

    @Test
    void biomesCollapseOntoTheCoarserGrid() {
        // 64 cells share one biome slot, so a representative is inherent. First writer wins, and the
        // slot must be the one covering that cell.
        long[] voxy = voxySection();
        voxy[vIdx(5, 6, 7)] = cell(11, 42);
        int[] cells = new int[LodNative.CELLS];
        int[] biomes = new int[LodNative.BIOME_CELLS];
        StoreTransfer.octantToOurs(voxy, 0, 0, 0, reader, cells, biomes);
        int slot = ((6 / 4) * LodNative.BIOME_EDGE + (7 / 4)) * LodNative.BIOME_EDGE + (5 / 4);
        assertEquals(42, biomes[slot]);
    }

    @Test
    void outputBuffersAreClearedBetweenOctants() {
        // The caller reuses them across every octant of every section. Left dirty, one octant's
        // terrain would bleed into the next — and it would look like real terrain.
        long[] voxy = voxySection();
        voxy[vIdx(0, 0, 0)] = cell(11, 3);
        int[] cells = new int[LodNative.CELLS];
        int[] biomes = new int[LodNative.BIOME_CELLS];
        StoreTransfer.octantToOurs(voxy, 0, 0, 0, reader, cells, biomes);
        StoreTransfer.octantToOurs(voxy, 1, 1, 1, reader, cells, biomes);
        for (int v : cells) assertEquals(LodNative.AIR, v, "stale cell from the previous octant");
        for (int v : biomes) assertEquals(0, v, "stale biome from the previous octant");
    }

    @Test
    void voxySectionsOutsideTheAbyssBandAreRejected() {
        // Shifted Y only means something inside the Abyss column. Converting outside it would invent
        // a sector and write terrain to an arbitrary place.
        assertNull(StoreTransfer.voxyToOurs(0, 0, 100000, 0));
        assertNull(StoreTransfer.voxyToOurs(0, 0, -100000, 0));
        assertNotNull(StoreTransfer.voxyToOurs(0, 0, 0, 0));
    }

    @Test
    void ourSectionsAreAQuarterOfAVoxyOneSoFourMapIntoEach() {
        // Sector 8 zeroes the vertical offset, which keeps this readable. Four of our sections span
        // one Voxy section along an axis (32 cells / 16 cells... at the same level, 2 per axis in
        // cells but our SECTION is half the span, so 2 per axis).
        int[] a = StoreTransfer.oursToVoxy(0, 0, 0, 0, 8);
        int[] b = StoreTransfer.oursToVoxy(0, 1, 0, 0, 8);
        assertArrayEquals(a, b, "adjacent sections of ours share a Voxy section");
        int[] c = StoreTransfer.oursToVoxy(0, 2, 0, 0, 8);
        assertEquals(a[0] + 1, c[0], "two of ours per Voxy section along an axis");
    }

    @Test
    void octantOfIdentifiesWhichEighthWeOccupy() {
        int[] o0 = StoreTransfer.octantOf(0, 0, 0, 0, 8);
        int[] o1 = StoreTransfer.octantOf(0, 1, 1, 1, 8);
        assertArrayEquals(new int[]{0, 0, 0}, new int[]{o0[0], o0[1], o0[2]});
        assertArrayEquals(new int[]{1, 1, 1}, new int[]{o1[0], o1[1], o1[2]});
    }

    @Test
    void theSectorRecoveredFromShiftedYAgreesWithMapGeometry() {
        // voxyToOurs recovers the sector from shifted Y. If that ever disagreed with the function the
        // map itself uses, imported terrain would land a whole layer away.
        for (int vsy : new int[]{-100, -20, 0, 40, 100}) {
            long shiftedY = (long) vsy * 32;
            if (shiftedY > MapGeometry.ABYSS_SHIFTED_Y_TOP
                    || shiftedY < MapGeometry.ABYSS_SHIFTED_Y_BOTTOM) continue;
            int sector = MapGeometry.sectorForShiftedY(shiftedY, -1);
            int[] ours = StoreTransfer.voxyToOurs(0, 0, vsy, 0);
            assertNotNull(ours);
            long expectedWorldY = shiftedY + LodTileAddress.verticalOffset(sector);
            assertEquals(Math.floorDiv(expectedWorldY, 16), ours[1], "vsy " + vsy);
        }
    }
}
