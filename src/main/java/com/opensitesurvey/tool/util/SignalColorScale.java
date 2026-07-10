package com.opensitesurvey.tool.util;

import javafx.scene.paint.Color;

/** Shared dBm -&gt; color mapping used by both the live dashboard and the survey heatmap. */
public final class SignalColorScale {

    private SignalColorScale() {
    }

    public static Color colorFor(double rssiDbm) {
        if (rssiDbm >= -50) return Color.web("#2ecc71");   // excellent
        if (rssiDbm >= -60) return Color.web("#a6d94a");   // good
        if (rssiDbm >= -70) return Color.web("#f1c40f");   // fair
        if (rssiDbm >= -80) return Color.web("#e67e22");   // poor
        return Color.web("#e74c3c");                        // very poor
    }

    public static String toWeb(Color c) {
        return ColorUtil.toWeb(c);
    }
}
