package com.opensitesurvey.tool.model;

import java.time.Instant;
import java.util.List;

/** Result of one polling cycle: every AP visible in the driver's cached BSS list at that moment. */
public record ScanSnapshot(Instant timestamp, List<ApSnapshot> accessPoints) {
}
