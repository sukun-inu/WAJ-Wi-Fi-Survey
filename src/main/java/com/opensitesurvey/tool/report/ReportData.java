package com.opensitesurvey.tool.report;

import com.opensitesurvey.tool.model.ApSnapshot;
import com.opensitesurvey.tool.model.SurveyPoint;
import javafx.scene.image.Image;

import java.time.Instant;
import java.util.List;

/** Everything a site-survey report needs, gathered from whichever tabs have data at export time. */
public record ReportData(
        Instant generatedAt,
        String interfaceDescription,
        List<ApSnapshot> accessPoints,
        List<SurveyPoint> surveyPoints,
        Image floorPlanSnapshot // nullable - null if no floor plan was loaded in Site Survey
) {
}
