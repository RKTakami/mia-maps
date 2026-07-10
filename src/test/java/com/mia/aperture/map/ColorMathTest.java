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
    void punchKeepsGreyGrey() {
        int grey = 0xFF808080;
        int p = ColorMath.punch(grey, 1.5f, 1.2f);
        assertEquals((p >> 16) & 0xFF, (p >> 8) & 0xFF);
        assertEquals((p >> 8) & 0xFF, p & 0xFF);
    }

    @Test
    void punchIncreasesSaturation() {
        int muted = 0xFF907868;
        int before = ((muted >> 16) & 0xFF) - (muted & 0xFF);
        int p = ColorMath.punch(muted, 1.5f, 1.0f);
        int after = ((p >> 16) & 0xFF) - (p & 0xFF);
        assertTrue(after > before, "expected wider channel spread, before=" + before + " after=" + after);
        assertEquals(0xFF, (p >>> 24) & 0xFF);
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
