package com.mia.aperture.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MobTrackerTest {

    // Unicode PRIVATE USE codepoints — how MIA's resource pack maps its custom-font branding
    // glyphs. Drawn raw these render as logo art. Built from codepoints so the escape survives
    // any source-encoding round trip.
    private static final String GLYPH_A = new String(Character.toChars(0xE001));
    private static final String GLYPH_B = new String(Character.toChars(0xF8FF));
    private static final String CTRL = String.valueOf((char) 7); // BEL

    @Test
    void stripsTrailingHealthReadout() {
        assertEquals("Cyatoria", MobTracker.cleanName("Cyatoria 16/25"));
        assertEquals("Cyatoria", MobTracker.cleanName("Cyatoria\n16/25"));
    }

    @Test
    void stripsPrivateUseGlyphs() {
        // the stray "MINE IN ABYSS" logo on the map came from these reaching drawString
        assertEquals("Vinebinder", MobTracker.cleanName(GLYPH_A + "Vinebinder" + GLYPH_B));
        assertEquals("Beniguma", MobTracker.cleanName("Beniguma" + GLYPH_A));
    }

    @Test
    void glyphOnlyNameIsEmptySoCallerCanFallBack() {
        // resolveName only accepts a non-empty result, so a pure-logo nameplate falls through
        // to the model id (or "Mob") instead of drawing glyph art.
        assertTrue(MobTracker.cleanName(GLYPH_A + GLYPH_B).isEmpty());
        assertTrue(MobTracker.cleanName(null).isEmpty());
        assertTrue(MobTracker.cleanName("").isEmpty());
    }

    @Test
    void stripsControlCharacters() {
        assertEquals("Abc", MobTracker.cleanName("Ab" + CTRL + "c"));
    }

    @Test
    void capsAbsurdlyLongNames() {
        assertTrue(MobTracker.cleanName("A".repeat(200)).length() <= 24);
    }

    @Test
    void keepsOrdinaryNames() {
        assertEquals("Beniguma", MobTracker.cleanName("Beniguma"));
        assertEquals("Corpse-Weeper", MobTracker.cleanName("Corpse-Weeper"));
    }
}
