package com.opensitesurvey.tool.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opensitesurvey.tool.i18n.Messages;

import java.io.File;
import java.io.IOException;

/** Loads/saves {@link AppConfig}, defaulting to {@link AppPaths#settingsFile()}. */
public final class AppConfigStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private AppConfigStore() {
    }

    public static AppConfig load() {
        return load(AppPaths.settingsFile());
    }

    public static AppConfig load(File file) {
        if (!file.exists()) {
            return new AppConfig();
        }
        try {
            return MAPPER.readValue(file, AppConfig.class);
        } catch (IOException e) {
            return new AppConfig();
        }
    }

    public static void save(AppConfig config) {
        save(config, AppPaths.settingsFile());
    }

    public static void save(AppConfig config, File file) {
        try {
            AtomicJsonWriter.write(MAPPER, config, file);
        } catch (IOException e) {
            throw new RuntimeException(Messages.get("config.error.saveFailed"), e);
        }
    }
}
