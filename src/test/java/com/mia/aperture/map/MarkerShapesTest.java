package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkerShapesTest {

    @Test
    void radiusZeroIsASinglePixel() {
        assertEquals(0, MarkerShapes.rowHalfWidth(0, 0));
        assertEquals(-1, MarkerShapes.rowHalfWidth(0, 1));
        assertEquals(-1, MarkerShapes.rowHalfWidth(0, -1));
    }

    @Test
    void widestRowIsAtTheCentre() {
        assertEquals(3, MarkerShapes.rowHalfWidth(3, 0));
        assertEquals(5, MarkerShapes.rowHalfWidth(5, 0));
    }

    @Test
    void rowsNarrowTowardThePoles() {
        assertEquals(4, MarkerShapes.rowHalfWidth(5, 3)); // round(sqrt(25-9)) = 4
        assertEquals(3, MarkerShapes.rowHalfWidth(5, 4)); // sqrt(25-16) = 3
        assertEquals(0, MarkerShapes.rowHalfWidth(5, 5));
    }

    @Test
    void rowsOutsideTheDiscAreRejected() {
        assertEquals(-1, MarkerShapes.rowHalfWidth(3, 4));
        assertEquals(-1, MarkerShapes.rowHalfWidth(3, -4));
    }

    @Test
    void discIsVerticallySymmetric() {
        for (int r = 1; r <= 7; r++) {
            for (int dy = 0; dy <= r; dy++) {
                assertEquals(MarkerShapes.rowHalfWidth(r, dy), MarkerShapes.rowHalfWidth(r, -dy),
                        "r=" + r + " dy=" + dy);
            }
        }
    }
}
