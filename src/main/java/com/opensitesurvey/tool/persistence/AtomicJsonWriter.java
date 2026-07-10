package com.opensitesurvey.tool.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Shared write-then-rename helper for {@link AppConfigStore} and {@link SurveyProjectStore}:
 * writing straight to the destination file leaves a truncated/corrupt JSON file behind if the
 * process is killed or the disk fills up mid-write, which then fails to load next launch. Writing
 * to a sibling temp file first and only then moving it over the real destination means the
 * destination file is only ever replaced by a fully-written one.
 */
final class AtomicJsonWriter {

    private AtomicJsonWriter() {
    }

    static void write(ObjectMapper mapper, Object value, File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        File tmp = File.createTempFile(file.getName(), ".tmp", parent);
        try {
            mapper.writeValue(tmp, value);
            try {
                Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }
}
