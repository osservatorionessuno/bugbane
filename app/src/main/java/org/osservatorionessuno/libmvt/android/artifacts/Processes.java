package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.IndicatorType;

import java.util.HashMap;
import java.util.Map;

/**
 * Parser for the output of the `ps` command.
 */
public class Processes extends AndroidArtifact {
    @Override
    public void parse(String input) {
        results.clear();
        String[] lines = input.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            // Sometimes WCHAN is empty or label present; adjust length
            if (parts.length == 8) {
                String[] newParts = new String[9];
                System.arraycopy(parts, 0, newParts, 0, 5);
                newParts[5] = "";
                System.arraycopy(parts, 5, newParts, 6, 3);
                parts = newParts;
            }
            int idx = 0;
            String label = "";
            if (parts[0].startsWith("u:r")) {
                label = parts[0];
                parts = java.util.Arrays.copyOfRange(parts, 1, parts.length);
            }
            if (parts.length < 9) {
                String[] newParts = new String[9];
                System.arraycopy(parts, 0, newParts, 0, 5);
                newParts[5] = "";
                System.arraycopy(parts, 5, newParts, 6, parts.length - 5);
                parts = newParts;
            }
            Map<String, Object> rec = new HashMap<>();
            rec.put("user", parts[0]);
            rec.put("pid", Integer.parseInt(parts[1]));
            rec.put("ppid", Integer.parseInt(parts[2]));
            rec.put("virtual_memory_size", Integer.parseInt(parts[3]));
            rec.put("resident_set_size", Integer.parseInt(parts[4]));
            rec.put("wchan", parts[5]);
            rec.put("aprocress", parts[6]);
            rec.put("stat", parts[7]);
            rec.put("proc_name", parts[8].replace("[", "").replace("]", ""));
            rec.put("label", label);
            results.add(rec);
        }
    }

    @Override
    public void checkIndicators() {
        if (indicators == null) return;
        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;
            String name = (String) map.get("proc_name");
            // We skip this process because of false positives.
            if ("gatekeeperd".equals(name)) continue;
            
            detected.addAll(indicators.matchString(name, IndicatorType.APP_ID));
            detected.addAll(indicators.matchString(name, IndicatorType.PROCESS));
        }
    }
}
