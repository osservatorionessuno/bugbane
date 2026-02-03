package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.Indicators.IndicatorType;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.InputStream;
import java.io.IOException;

public class DumpsysAccessibility extends AndroidArtifact {

    @Override
    public List<String> paths() {
        return List.of("dumpsys.txt");
    }

    @Override
    public void parse(InputStream input) throws IOException {
        results.clear();
        List<String> lines = collectLines(input);
        Pattern legacyPattern = Pattern.compile("\\s*(\\d+) : (.+)");
        Pattern v14Pattern = Pattern.compile("\\{\\{(.+?)\\}\\}", Pattern.DOTALL);

        boolean legacy = false;
        boolean v14 = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("installed services:")) {
                legacy = true;
                for (int j = i + 1; j < lines.size(); j++) {
                    Matcher m = legacyPattern.matcher(lines.get(j));
                    if (m.find()) {
                        String fullService = m.group(2).trim();
                        String packageName = fullService.split("/")[0];
                        String service = fullService;
                        Map<String, String> result = new HashMap<>();
                        result.put("package_name", packageName);
                        result.put("service", service);
                        results.add(result);
                    } else if (lines.get(j).trim().startsWith("}")) {
                        break;
                    }
                }
            } else if (line.trim().startsWith("Enabled services:")) {
                v14 = true;
                for (int j = i; j < lines.size(); j++) {
                    Matcher m = v14Pattern.matcher(lines.get(j));
                    if (m.find()) {
                        String fullService = m.group(1).trim();
                        String[] parts = fullService.split("/");
                        String packageName = parts[0];
                        String service = parts.length > 1 ? parts[1] : "";
                        Map<String, String> result = new HashMap<>();
                        result.put("package_name", packageName);
                        result.put("service", service.isEmpty() ? fullService : service);
                        results.add(result);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void checkIndicators() {
        if (indicators == null) return;
        
        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, String> record = (Map<String, String>) obj;
            String pkg = record.get("package_name");
            detected.addAll(indicators.matchString(pkg, IndicatorType.APP_ID));
        }
    }
}