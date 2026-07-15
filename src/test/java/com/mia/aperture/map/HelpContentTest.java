package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HelpContentTest {
    // stub resolver: echoes the action so tests can spot resolved keys
    private final HelpContent.KeyResolver keys = action -> "<" + action + ">";

    @Test
    void everyTabHasContent() {
        for (HelpContent.Tab t : HelpContent.Tab.values()) {
            List<HelpContent.Line> lines = HelpContent.lines(t, keys);
            assertFalse(lines.isEmpty(), "tab " + t + " should have content");
        }
    }

    @Test
    void noLineHasEmptyText() {
        for (HelpContent.Tab t : HelpContent.Tab.values())
            for (HelpContent.Line ln : HelpContent.lines(t, keys))
                assertFalse(ln.text() == null || ln.text().isBlank(), "empty line in " + t);
    }

    @Test
    void keysTabUsesResolvedOpenMapKey() {
        boolean found = HelpContent.lines(HelpContent.Tab.KEYS, keys).stream()
                .anyMatch(ln -> "<open_map>".equals(ln.key()));
        assertTrue(found, "KEYS tab should list the resolved open-map key");
    }

    @Test
    void everyTabHasAHeading() {
        for (HelpContent.Tab t : HelpContent.Tab.values())
            assertTrue(HelpContent.lines(t, keys).stream().anyMatch(HelpContent.Line::heading),
                    "tab " + t + " should start with a heading");
    }
}
