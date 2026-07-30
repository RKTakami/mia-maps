package com.mia.aperture.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceTest {
    private static int over(int src, int dst) { return Surface.Image.over(src, dst); }

    @Test
    void opaqueSourceReplaces() {
        assertEquals(0xFFC49A48, over(0xFFC49A48, 0xFF000000));
        assertEquals(0xFFC49A48, over(0xFFC49A48, 0x00000000));
    }

    @Test
    void onEmptyPixelTheSourceSurvivesUnchanged() {
        // The ornament's first stroke lands on a cleared texture. If a translucent source were
        // composited against transparent black here it would come out darkened, and every shadow
        // would bake in twice as heavy as it draws on screen.
        assertEquals(0x66000000, over(0x66000000, 0x00000000));
        assertEquals(0x30FFFFFF, over(0x30FFFFFF, 0x00000000));
    }

    @Test
    void halfBlackOverWhiteLandsInTheMiddle() {
        int r = over(0x80000000, 0xFFFFFFFF);
        assertEquals(0xFF, r >>> 24, "over an opaque pixel the result stays opaque");
        int grey = r & 0xFF;
        assertTrue(grey > 100 && grey < 140, "expected mid grey, got " + Integer.toHexString(r));
        assertEquals(grey, (r >> 8) & 0xFF, "channels must stay neutral");
        assertEquals(grey, (r >> 16) & 0xFF);
    }

    @Test
    void alphaAccumulatesButNeverOverflows() {
        int acc = 0x00000000;
        for (int i = 0; i < 12; i++) acc = over(0x40FF0000, acc);
        int a = acc >>> 24;
        assertTrue(a > 0xF0, "repeated layering should approach opaque, got " + a);
        assertTrue(a <= 0xFF, "alpha overflowed: " + a);
        assertTrue(((acc >> 16) & 0xFF) <= 0xFF && ((acc >> 16) & 0xFF) > 0xE0,
                "the red should saturate, not wrap: " + Integer.toHexString(acc));
    }
}
