package com.waj.tool.model;

import java.util.ArrayList;
import java.util.List;

/** Persisted site-survey project: floor plan reference, scale, and every recorded point. */
public class SurveyProject {

    public String floorPlanPath;
    public double metersPerPixel;
    public List<SurveyPoint> points = new ArrayList<>();

    public SurveyProject() {
    }

    public SurveyProject(String floorPlanPath, double metersPerPixel, List<SurveyPoint> points) {
        this.floorPlanPath = floorPlanPath;
        this.metersPerPixel = metersPerPixel;
        this.points = points;
    }
}
