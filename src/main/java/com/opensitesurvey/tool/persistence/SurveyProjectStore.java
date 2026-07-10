package com.opensitesurvey.tool.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opensitesurvey.tool.model.SurveyProject;

import java.io.File;
import java.io.IOException;

/** Reads/writes a {@link SurveyProject} as JSON. */
public final class SurveyProjectStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private SurveyProjectStore() {
    }

    public static void save(SurveyProject project, File file) throws IOException {
        AtomicJsonWriter.write(MAPPER, project, file);
    }

    public static SurveyProject load(File file) throws IOException {
        return MAPPER.readValue(file, SurveyProject.class);
    }
}
