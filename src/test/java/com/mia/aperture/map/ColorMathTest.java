package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColorMathTest {

    @Test
    void averageOfSolidOpaqueIsThatColour() {
        int[] px = { 0xFF804020, 0xFF804020, 0xFF804020, 0xFF804020 };
        assertEquals(0xFF804020, ColorMath.alphaWeightedAverage(px));
    }

    @Test
    void fullyTransparentPixelsAreIgnored() {
        int[] px = { 0xFFFF0000, 0x00000000, 0x00000000, 0x00000000 };
        int avg = ColorMath.alphaWeightedAverage(px);
        assertEquals(255, (avg >> 24) & 0xFF);
        assertEquals(255, (avg >> 16) & 0xFF);
        assertEquals(0, (avg >> 8) & 0xFF);
        assertEquals(0, avg & 0xFF);
    }

    @Test
    void allTransparentYieldsZero() {
        int[] px = { 0x00112233, 0x00445566 };
        assertEquals(0, ColorMath.alphaWeightedAverage(px));
    }

    @Test
    void averageMixesRgbByAlphaWeight() {
        int[] px = { 0x80000000, 0xFFFFFFFF };
        int avg = ColorMath.alphaWeightedAverage(px);
        int r = (avg >> 16) & 0xFF;
        assertTrue(r > 160 && r < 180, "expected ~170, got " + r);
    }

    @Test
    void tintMultiplyScalesChannels() {
        int base = 0xFF808080;
        int tint = 0x40FF40;
        int out = ColorMath.tintMultiply(base, tint);
        assertEquals(0xFF, (out >> 24) & 0xFF);
        assertEquals(0x20, (out >> 16) & 0xFF);
        assertEquals(0x80, (out >> 8) & 0xFF);
        assertEquals(0x20, out & 0xFF);
    }
}
