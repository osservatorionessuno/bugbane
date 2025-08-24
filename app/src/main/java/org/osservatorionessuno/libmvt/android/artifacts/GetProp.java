package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.IndicatorType;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the output of the `getprop` command.
 */
public class GetProp extends AndroidArtifact {
    private static final Pattern PATTERN = Pattern.compile("\\[(.+?)\\]: \\[(.*?)\\]");

    @Override
    public void parse(String input) {
        results.clear();
        for (String line : input.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher m = PATTERN.matcher(line);
            if (!m.find() || m.groupCount() < 2) continue;
            Map<String, String> entry = new HashMap<>();
            entry.put("name", m.group(1));
            entry.put("value", m.group(2));
            results.add(entry);
        }
    }

    @Override
    public void checkIndicators() {
        if (indicators == null) return;
        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, String> map = (Map<String, String>) obj;
            String name = map.get("name");
            detected.addAll(indicators.matchString(name, IndicatorType.PROPERTY));
        }
    }

    /** Helper to obtain the timezone property value. */
    public String getDeviceTimezone() {
        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, String> map = (Map<String, String>) obj;
            if ("persist.sys.timezone".equals(map.get("name"))) {
                return map.get("value");
            }
        }
        return null;
    }
}
