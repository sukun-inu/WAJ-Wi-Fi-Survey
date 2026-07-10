package com.opensitesurvey.tool.util;

import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

/**
 * Shared tooltip factory with a gentle show delay so hover help stays informative but unobtrusive.
 */
public final class TooltipSupport {

    private TooltipSupport() {
    }

    public static Tooltip create(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.millis(450));
        tooltip.setShowDuration(Duration.seconds(24));
        tooltip.setHideDelay(Duration.millis(100));
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(360);
        return tooltip;
    }

    public static void set(Control control, String text) {
        control.setTooltip(create(text));
    }

    public static void set(Node node, String text) {
        Tooltip.install(node, create(text));
    }

    public static void install(Node node, String text) {
        Tooltip.install(node, create(text));
    }
}
