package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.Indicators.IndicatorType;

import java.util.*;
import java.io.IOException;
import java.io.InputStream;

/** Parser for dumpsys battery daily output. */
public class DumpsysBatteryDaily extends AndroidArtifact {

    @Override
    public List<String> paths() {
        return List.of("dumpsys.txt");
    }

    @Override
    public void parse(InputStream input) throws IOException {
        results.clear();
        Map<String, String> daily = null;
        List<Map<String, String>> updates = new ArrayList<>();
        for (String line : collectLines(input)) {
            if (line.startsWith("  Daily from ")) {
                if (!updates.isEmpty()) {
                    results.addAll(updates);
                    updates.clear();
                }
                String tf = line.substring(13).trim();
                String[] parts = tf.replace(":", "").split(" to ", 2);
                daily = new HashMap<>();
                daily.put("from", parts[0].substring(0, 10));
                daily.put("to", parts[1].substring(0, 10));
                continue;
            }
            if (daily == null) continue;
            String trimmed = line.trim();
            if (!trimmed.startsWith("Update ")) continue;
            trimmed = trimmed.substring(7);
            String[] parts = trimmed.split(" ", 2);
            String pkg = parts[0];
            String vers = parts[1].split("=",2)[1];
            boolean exists = false;
            for (Map<String, String> u : updates) {
                if (u.get("package_name").equals(pkg) && u.get("vers").equals(vers)) { exists = true; break; }
            }
            if (!exists) {
                Map<String, String> rec = new HashMap<>();
                rec.put("action", "update");
                rec.put("from", daily.get("from"));
                rec.put("to", daily.get("to"));
                rec.put("package_name", pkg);
                rec.put("vers", vers);
                updates.add(rec);
            }
        }
        if (!updates.isEmpty()) results.addAll(updates);
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
