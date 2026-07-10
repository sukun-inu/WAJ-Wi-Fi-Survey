package com.opensitesurvey.tool.alert;

import com.opensitesurvey.tool.model.ScanSnapshot;

import java.util.List;

/** One alerting rule, evaluated once per scan snapshot. Implementations track their own state
 * transitions (e.g. via {@link AlertContext}) so they fire once per transition, not once per cycle. */
public interface AlertRule {
    List<Alert> evaluate(ScanSnapshot snapshot, AlertContext context);
}
