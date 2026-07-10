package com.waj.tool.persistence;

import java.io.File;

/** Per-user application data directory: {@code ~/.waj-wifi-survey/}. */
public final class AppPaths {

    private AppPaths() {
    }

    public static File appDataDir() {
        File dir = new File(System.getProperty("user.home"), ".waj-wifi-survey");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File settingsFile() {
        return new File(appDataDir(), "settings.json");
    }

    public static File scanLogDbFile() {
        return new File(appDataDir(), "scan-log.db");
    }
}
