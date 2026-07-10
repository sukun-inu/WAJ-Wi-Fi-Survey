package com.opensitesurvey.tool.util;

import javafx.scene.Scene;
import javafx.scene.control.Alert;

/**
 * Applies the app's dark stylesheet to secondary windows. {@code App}'s primary Scene picks up
 * {@code style.css} directly, but every {@code Alert} and the {@code SettingsDialog}'s own {@code
 * Stage} construct their own independent Scene/DialogPane that does NOT inherit it automatically -
 * left unstyled, they render in the stock light Modena look, sitting jarringly next to the rest of
 * the dark-themed app (and specific controls, e.g. a disabled TextField's prompt text, are close to
 * unreadable against Modena's default light background at this app's font size).
 */
public final class AppTheme {

    private static final String STYLESHEET = "/style.css";

    private AppTheme() {
    }

    public static void apply(Scene scene) {
        String url = AppTheme.class.getResource(STYLESHEET).toExternalForm();
        if (!scene.getStylesheets().contains(url)) {
            scene.getStylesheets().add(url);
        }
    }

    public static void apply(Alert alert) {
        String url = AppTheme.class.getResource(STYLESHEET).toExternalForm();
        if (!alert.getDialogPane().getStylesheets().contains(url)) {
            alert.getDialogPane().getStylesheets().add(url);
        }
    }
}
