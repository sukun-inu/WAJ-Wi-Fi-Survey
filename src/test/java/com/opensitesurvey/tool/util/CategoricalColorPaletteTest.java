package com.opensitesurvey.tool.util;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoricalColorPaletteTest {

    @Test
    void sameKeyAlwaysGetsTheSameColor() {
        CategoricalColorPalette palette = new CategoricalColorPalette();
        Color first = palette.colorFor("AA:BB:CC:DD:EE:FF");
        Color second = palette.colorFor("AA:BB:CC:DD:EE:FF");
        assertEquals(first, second);
    }

    @Test
    void everyColorIsDistinctEvenWellBeyondTheFixedPaletteSize() {
        CategoricalColorPalette palette = new CategoricalColorPalette();
        Set<Color> colors = new HashSet<>();
        // Comfortably more than the 12-entry hand-picked palette, matching a dense real-world
        // Wi-Fi environment (many APs each broadcasting several BSSIDs across bands).
        for (int i = 0; i < 40; i++) {
            Color c = palette.colorFor("bssid-" + i);
            assertTrue(colors.add(c), "color collided for key index " + i);
        }
    }
}
