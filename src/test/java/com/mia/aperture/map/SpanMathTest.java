package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpanMathTest {

    @Test
    void spanPackingRoundTripsIncludingNegatives() {
        int s = SpanMath.packSpan(271, -226);
        assertEquals(271, SpanMath.spanTop(s));
        assertEquals(-226, SpanMath.spanBottom(s));
        int t = SpanMath.packSpan(0, 0);
        assertEquals(0, SpanMath.spanTop(t));
        assertEquals(0, SpanMath.spanBottom(t));
    }

    @Test
    void insertIntoEmptyColumnCreatesOneSpan() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 10, 5, 0xFF112233);
        assertEquals(1, c.spans().length);
        assertEquals(10, SpanMath.spanTop(c.spans()[0]));
        assertEquals(5, SpanMath.spanBottom(c.spans()[0]));
        assertEquals(0xFF112233, c.colors()[0]);
    }

    @Test
    void disjointRunsStaySeparateAndSorted() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 10, 5, 1);
        c = SpanMath.insertRun(c, -3, -8, 2);
        assertEquals(2, c.spans().length);
        assertEquals(-8, SpanMath.spanBottom(c.spans()[0]));
        assertEquals(5, SpanMath.spanBottom(c.spans()[1]));
    }

    @Test
    void overlappingAndAdjacentRunsMerge() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 10, 5, 1);
        c = SpanMath.insertRun(c, 4, 0, 2);      // adjacent below (4 touches 5)
        assertEquals(1, c.spans().length);
        assertEquals(10, SpanMath.spanTop(c.spans()[0]));
        assertEquals(0, SpanMath.spanBottom(c.spans()[0]));
        c = SpanMath.insertRun(c, 15, 8, 3);     // overlapping above
        assertEquals(1, c.spans().length);
        assertEquals(15, SpanMath.spanTop(c.spans()[0]));
        assertEquals(0, SpanMath.spanBottom(c.spans()[0]));
    }

    @Test
    void mergeKeepsTheColorOfTheHighestTop() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 10, 5, 111);
        c = SpanMath.insertRun(c, 8, 3, 222);    // merges, but 10 is still the top
        assertEquals(111, c.colors()[0]);
        c = SpanMath.insertRun(c, 14, 9, 333);   // new top 14 wins
        assertEquals(333, c.colors()[0]);
    }

    @Test
    void clearRangeRemovesTrimsAndSplits() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 20, 0, 7);
        c = SpanMath.clearRange(c, 12, 8);       // punch a hole in the middle
        assertEquals(2, c.spans().length);
        assertEquals(7, SpanMath.spanTop(c.spans()[0]));
        assertEquals(0, SpanMath.spanBottom(c.spans()[0]));
        assertEquals(20, SpanMath.spanTop(c.spans()[1]));
        assertEquals(13, SpanMath.spanBottom(c.spans()[1]));
        c = SpanMath.clearRange(c, 25, 13);      // remove the upper fragment entirely
        assertEquals(1, c.spans().length);
        AbyssSpanStore.Column gone = SpanMath.clearRange(c, 30, -30);
        assertNull(gone);
    }

    @Test
    void solidAtChecksMembership() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 10, 5, 1);
        assertTrue(SpanMath.solidAt(c, 5));
        assertTrue(SpanMath.solidAt(c, 10));
        assertFalse(SpanMath.solidAt(c, 4));
        assertFalse(SpanMath.solidAt(c, 11));
        assertFalse(SpanMath.solidAt(null, 0));
    }

    @Test
    void mipHalvesYAndUnionsOccupancy() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 15, 8, 5);
        c = SpanMath.insertRun(c, 1, 0, 9);
        AbyssSpanStore.Column m = SpanMath.mipInto(null, c);
        assertEquals(2, m.spans().length);
        assertEquals(7, SpanMath.spanTop(m.spans()[1]));
        assertEquals(4, SpanMath.spanBottom(m.spans()[1]));
        assertEquals(0, SpanMath.spanTop(m.spans()[0]));
        assertEquals(0, SpanMath.spanBottom(m.spans()[0]));
    }

    @Test
    void mipOfNegativeYUsesFloorDivision() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, -5, -8, 1);
        AbyssSpanStore.Column m = SpanMath.mipInto(null, c);
        assertEquals(-3, SpanMath.spanTop(m.spans()[0]));   // floorDiv(-5,2) = -3
        assertEquals(-4, SpanMath.spanBottom(m.spans()[0])); // floorDiv(-8,2) = -4
    }
}
