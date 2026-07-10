package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaveDetectorTest {

    @Test
    void debounceNeedsConsecutiveTicksToFlip() {
        CaveDetector d = new CaveDetector();
        for (int i = 0; i < CaveDetector.DEBOUNCE_TICKS - 1; i++) {
            assertFalse(d.debounce(true));
        }
        assertTrue(d.debounce(true));
    }

    @Test
    void debounceResetsOnFlicker() {
        CaveDetector d = new CaveDetector();
        for (int i = 0; i < CaveDetector.DEBOUNCE_TICKS - 3; i++) d.debounce(true);
        assertFalse(d.debounce(false));
        for (int i = 0; i < CaveDetector.DEBOUNCE_TICKS - 1; i++) assertFalse(d.debounce(true));
        assertTrue(d.debounce(true));
    }

    @Test
    void caveActiveTruthTable() {
        assertTrue(CaveDetector.caveActive(MapSettings.CaveMode.ON, false));
        assertFalse(CaveDetector.caveActive(MapSettings.CaveMode.OFF, true));
        assertTrue(CaveDetector.caveActive(MapSettings.CaveMode.AUTO, true));
        assertFalse(CaveDetector.caveActive(MapSettings.CaveMode.AUTO, false));
    }
}
