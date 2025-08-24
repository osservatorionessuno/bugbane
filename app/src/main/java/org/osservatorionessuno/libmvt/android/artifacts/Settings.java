package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.Detection;
import org.osservatorionessuno.libmvt.common.IndicatorType;

import java.util.*;

/** Parser for Android settings files. */
public class Settings extends AndroidArtifact {
    private static class DangerousSetting {
        String key;
        String safeValue;
        String description;
    }

    private static final List<DangerousSetting> DANGEROUS_SETTINGS = new ArrayList<>();

    static {
        add("verifier_verify_adb_installs", "1", "disabled Google Play Services apps verification");
        add("package_verifier_enable", "1", "disabled Google Play Protect");
        add("package_verifier_state", "1", "disabled APK package verification");
        add("package_verifier_user_consent", "1", "disabled Google Play Protect");
        add("upload_apk_enable", "1", "disabled Google Play Protect");
        add("adb_install_need_confirm", "1", "disabled confirmation of adb apps installation");
        add("send_security_reports", "1", "disabled sharing of security reports");
        add("samsung_errorlog_agree", "1", "disabled sharing of crash logs with manufacturer");
        add("send_action_app_error", "1", "disabled applications errors reports");
        add("accessibility_enabled", "0", "enabled accessibility services");
    }

    private static void add(String key, String safeVal, String desc) {
        DangerousSetting ds = new DangerousSetting();
        ds.key = key; ds.safeValue = safeVal; ds.description = desc;
        DANGEROUS_SETTINGS.add(ds);
    }

    @Override
    public void parse(String input) {
        results.clear();
        Map<String, String> map = new HashMap<>();
        if (input != null) {
            for (String line : input.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || !line.contains("=")) continue;
                String[] parts = line.split("=", 2);
                map.put(parts[0], parts.length > 1 ? parts[1] : "");
            }
        }
        results.add(map);
    }

    @Override
    public void checkIndicators() {
        if (results.isEmpty()) return;
        @SuppressWarnings("unchecked")
        Map<String, String> settings = (Map<String, String>) results.get(0);
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            for (DangerousSetting ds : DANGEROUS_SETTINGS) {
                if (ds.key.equals(entry.getKey()) && !ds.safeValue.equals(entry.getValue())) {
                    // In this simplified implementation we just record a detection with description
                    detected.add(new Detection(
                            IndicatorType.PROCESS, ds.description, entry.getKey() + "=" + entry.getValue()));
                    break;
                }
            }
        }
    }
}
