package com.opensitesurvey.tool.util;

import javafx.scene.paint.Color;

/** Small shared helper so the "Color -> CSS hex string" conversion isn't copy-pasted per palette. */
public final class ColorUtil {

    private ColorUtil() {
    }

    public static String toWeb(Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}
