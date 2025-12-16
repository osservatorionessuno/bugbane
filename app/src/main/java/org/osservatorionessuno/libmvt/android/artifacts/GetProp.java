package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.AlertLevel;
import org.osservatorionessuno.libmvt.common.Detection;
import org.osservatorionessuno.libmvt.common.IndicatorType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the output of the `getprop` command.
 */
public class GetProp extends AndroidArtifact {
    private static final Pattern PATTERN = Pattern.compile("\\[(.+?)\\]: \\[(.*?)\\]");
    private static final Set<String> INTERESTING_PROPERTIES = Set.of(
        "gsm.sim.operator.alpha",
        "gsm.sim.operator.iso-country",
        "persist.sys.timezone",
        "ro.boot.serialno",
        "ro.build.version.sdk",
        "ro.build.version.security_patch",
        "ro.product.cpu.abi",
        "ro.product.locale",
        "ro.product.vendor.manufacturer",
        "ro.product.vendor.model",
        "ro.product.vendor.name"
    );

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
        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, String> map = (Map<String, String>) obj;
            String name = map.get("name");
            if (Objects.equals(name, "ro.build.version.security_patch")) {
                String patchLevel = map.get("value");
                if (daysSinceSecurityPatchLevel(patchLevel) > 180) {
                    String warningMessage = String.format(
                        "This phone has not received security updates for more than six months (last update: %s).",
                        patchLevel
                    );
                    detected.add(new Detection(AlertLevel.MEDIUM, IndicatorType.OTHER, warningMessage, name));
                }
                continue;
            }
            if (INTERESTING_PROPERTIES.contains(name)) {
                detected.add(new Detection(AlertLevel.LOG, IndicatorType.OTHER, map.get("value"), name));
            }
            // TODO: Check for model and manufacturer
        }

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

    private long daysSinceSecurityPatchLevel(String patchLevel) {
        if (patchLevel != null && patchLevel.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                java.time.LocalDate patchDate = java.time.LocalDate.parse(patchLevel);
                java.time.LocalDate now = java.time.LocalDate.now();
                // If more than 6 months have passed
                return java.time.temporal.ChronoUnit.DAYS.between(patchDate, now);
            } catch (Exception ignore) {
                // ignore parse errors
            }
        }
        return 0;
    }
}
