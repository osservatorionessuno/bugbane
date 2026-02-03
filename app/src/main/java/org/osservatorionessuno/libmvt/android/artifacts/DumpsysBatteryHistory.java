package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.Indicators.IndicatorType;

import java.util.*;
import java.io.IOException;
import java.io.InputStream;

/** Parser for dumpsys battery history output. */
public class DumpsysBatteryHistory extends AndroidArtifact {

    @Override
    public List<String> paths() {
        return List.of("dumpsys.txt");
    }

    @Override
    public void parse(InputStream input) throws IOException {
        results.clear();
        for (String line : collectLines(input)) {
            if (line.startsWith("Battery History ")) continue;
            if (line.trim().isEmpty()) break;
            String trimmed = line.trim();
            String[] parts = trimmed.split(" ", 2);
            String timeElapsed = parts.length > 0 ? parts[0] : "";
            String event = "";
            String uid = "";
            String service = "";
            String packageName = "";

            if (line.contains("+job")) {
                event = "start_job";
                int start = line.indexOf("+job") + 5;
                int colon = line.indexOf(':', start);
                if (colon < 0) continue;
                uid = line.substring(start, colon);
                service = line.substring(colon + 1).replace("\"", "").trim();
                packageName = service.split("/")[0];
            } else if (line.contains("-job")) {
                event = "end_job";
                int start = line.indexOf("-job") + 5;
                int colon = line.indexOf(':', start);
                if (colon < 0) continue;
                uid = line.substring(start, colon);
                service = line.substring(colon + 1).replace("\"", "").trim();
                packageName = service.split("/")[0];
            } else if (line.contains("+running +wake_lock=")) {
                int start = line.indexOf("+running +wake_lock=") + 21;
                int colon = line.indexOf(':', start);
                if (colon < 0) continue;
                uid = line.substring(start, colon);
                event = "wake";
                int walarm = line.indexOf("*walarm*:");
                if (walarm < 0) continue;
                service = line.substring(walarm + 9).split(" ")[0].replace("\"", "").trim();
                if (service.isEmpty() || !service.contains("/")) continue;
                packageName = service.split("/")[0];
            } else if (line.contains("+top=") || line.contains("-top")) {
                int topPos;
                if (line.contains("+top=")) {
                    event = "start_top";
                    topPos = line.indexOf("+top=");
                } else {
                    event = "end_top";
                    topPos = line.indexOf("-top");
                }
                int colon = line.indexOf(':', topPos);
                if (colon < 0) continue;
                uid = line.substring(topPos + 5, colon);
                packageName = line.substring(colon + 1).replace("\"", "").trim();
            } else {
                continue;
            }

            Map<String, String> map = new HashMap<>();
            map.put("time_elapsed", timeElapsed);
            map.put("event", event);
            map.put("uid", uid);
            map.put("package_name", packageName);
            map.put("service", service);
            results.add(map);
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
