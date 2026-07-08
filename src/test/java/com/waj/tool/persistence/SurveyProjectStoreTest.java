package com.waj.tool.persistence;

import com.waj.tool.model.SurveyPoint;
import com.waj.tool.model.SurveyProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurveyProjectStoreTest {

    @Test
    void roundTripsProjectThroughJson(@TempDir Path tempDir) throws Exception {
        SurveyPoint p1 = new SurveyPoint(0.25, 0.5, Map.of("AA:AA:AA:AA:AA:AA", -45), Instant.ofEpochSecond(1_700_000_000));
        SurveyPoint p2 = new SurveyPoint(0.75, 0.1, Map.of("AA:AA:AA:AA:AA:AA", -70, "BB:BB:BB:BB:BB:BB", -55), Instant.ofEpochSecond(1_700_000_100));
        SurveyProject original = new SurveyProject("C:/floorplans/office.png", 0.05, List.of(p1, p2));

        Path file = tempDir.resolve("test.wajproj.json");
        SurveyProjectStore.save(original, file.toFile());
        SurveyProject loaded = SurveyProjectStore.load(file.toFile());

        assertEquals(original.floorPlanPath, loaded.floorPlanPath);
        assertEquals(original.metersPerPixel, loaded.metersPerPixel);
        assertEquals(original.points.size(), loaded.points.size());
        assertEquals(original.points.get(0).xNorm, loaded.points.get(0).xNorm);
        assertEquals(original.points.get(0).rssiByBssid, loaded.points.get(0).rssiByBssid);
        assertEquals(original.points.get(1).rssiByBssid, loaded.points.get(1).rssiByBssid);
        assertEquals(original.points.get(1).epochSecond, loaded.points.get(1).epochSecond);
    }
}
