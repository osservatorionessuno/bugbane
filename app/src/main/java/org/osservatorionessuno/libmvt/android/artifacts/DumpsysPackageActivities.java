package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.IndicatorType;

import java.util.*;

/** Parser for dumpsys packages activities output. */
public class DumpsysPackageActivities extends AndroidArtifact {
    @Override
    public void parse(String content) {
        results.clear();
        boolean inActivityResolver = false;
        boolean inNonDataActions = false;
        String intent = null;
        for (String line : content.split("\n")) {
            if (line.startsWith("Activity Resolver Table:")) {
                inActivityResolver = true;
                continue;
            }
            if (!inActivityResolver) continue;
            if (line.startsWith("  Non-Data Actions:")) {
                inNonDataActions = true;
                continue;
            }
            if (!inNonDataActions) continue;
            if (line.trim().isEmpty()) break;
            if (line.startsWith("      ") && !line.startsWith("        ") && line.contains(":")) {
                intent = line.trim().replace(":", "");
                continue;
            }
            if (intent == null) continue;
            if (!line.startsWith("        ")) {
                intent = null;
                continue;
            }
            String[] parts = line.trim().split(" ");
            if (parts.length < 2) continue;
            String activity = parts[1];
            String packageName = activity.split("/")[0];
            Map<String, String> record = new HashMap<>();
            record.put("intent", intent);
            record.put("package_name", packageName);
            record.put("activity", activity);
            results.add(record);
        }
    }

    @Override
    public void checkIndicators() {
        if (indicators == null) return;
        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, String> rec = (Map<String, String>) obj;
            detected.addAll(indicators.matchString(rec.get("package_name"), IndicatorType.APP_ID));
        }
    }
}
