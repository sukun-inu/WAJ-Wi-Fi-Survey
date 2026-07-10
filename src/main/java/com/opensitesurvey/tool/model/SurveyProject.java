package com.waj.tool.model;

import java.util.ArrayList;
import java.util.List;

/** Persisted site-survey project: floor plan reference, scale, and every recorded point. */
public class SurveyProject {

    public String floorPlanPath;
    public double metersPerPixel;
    public List<SurveyPoint> points = new ArrayList<>();

    /**
     * Base64-encoded raw bytes of the floor plan image file, embedded directly in the project so
     * the project stays self-contained and portable (movable/shareable without also having to
     * carry the original image file at its original absolute path). {@code null} for project
     * files saved before this field existed, or if the source file couldn't be read at save time -
     * those fall back to resolving {@link #floorPlanPath} on load, same as before.
     */
    public String floorPlanImageBase64;

    public SurveyProject() {
    }

    public SurveyProject(String floorPlanPath, double metersPerPixel, List<SurveyPoint> points) {
        this.floorPlanPath = floorPlanPath;
        this.metersPerPixel = metersPerPixel;
        this.points = points;
    }
}
