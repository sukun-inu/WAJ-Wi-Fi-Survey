package com.waj.tool.util;

import javafx.scene.paint.Color;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assigns a stable, visually-distinct color per key (e.g. BSSID) so the same access point keeps
 * the same color everywhere it's drawn (AP table swatch, RSSI history line, spectrum curve),
 * regardless of its current signal strength. This is deliberately separate from
 * {@link SignalColorScale}, which encodes RSSI strength (green=strong..red=weak) rather than
 * identity - the two answer different questions ("how strong?" vs "which AP is this?").
 */
public final class CategoricalColorPalette {

    private static final Color[] PALETTE = {
            Color.web("#4E9BD8"), // blue
            Color.web("#E8804A"), // orange
            Color.web("#4FAE62"), // green
            Color.web("#D65F8A"), // pink
            Color.web("#9B7FD4"), // purple
            Color.web("#C9A227"), // gold
            Color.web("#4CBFC0"), // teal
            Color.web("#D65454"), // red
            Color.web("#8C6D4F"), // brown
            Color.web("#7A8C3E"), // olive
            Color.web("#5C7CBA"), // steel blue
            Color.web("#C97ED0"), // orchid
    };

    private final Map<String, Color> assigned = new LinkedHashMap<>();

    public Color colorFor(String key) {
        return assigned.computeIfAbsent(key, k -> {
            int index = assigned.size();
            if (index < PALETTE.length) {
                return PALETTE[index];
            }
            // Beyond the hand-picked palette (a realistic count in any moderately dense Wi-Fi
            // environment - an office/apartment building easily has 12+ distinct BSSIDs), wrapping
            // back to PALETTE[index % PALETTE.length] would silently hand out a color already in
            // use by an unrelated AP, defeating the whole point of an *identity* color. Golden-angle
            // hue stepping instead keeps generating new, well-separated hues indefinitely.
            double hue = (index * 137.508) % 360.0;
            return Color.hsb(hue, 0.55, 0.85);
        });
    }

    public static String toWeb(Color c) {
        return ColorUtil.toWeb(c);
    }
}
