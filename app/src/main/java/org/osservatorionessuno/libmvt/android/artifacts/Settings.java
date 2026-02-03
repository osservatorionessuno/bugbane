package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.bugbane.R;
import org.osservatorionessuno.libmvt.common.AlertLevel;
import org.osservatorionessuno.libmvt.common.Detection;

import java.util.*;
import java.io.InputStream;
import java.io.IOException;

/** Parser for Android settings files. */
public class Settings extends AndroidArtifact {
    private static class DangerousSetting {
        String key;
        String safeValue;
        String description;
    }

    private static final HashMap<String, DangerousSetting> DANGEROUS_SETTINGS = new HashMap<>();

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
        DANGEROUS_SETTINGS.put(key, ds);
    }

    @Override
    public List<String> paths() {
        return List.of("settings_system.txt", "settings_secure.txt", "settings_global.txt");
    }

    @Override
    public void parse(InputStream input) throws IOException {
        results.clear();
        Map<String, String> map = new HashMap<>();
        if (input != null) {
            for (String line : collectLines(input)) {
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
            DangerousSetting ds = DANGEROUS_SETTINGS.get(entry.getKey());
            if (ds != null &&
                !ds.safeValue.equals(entry.getValue())) {
                detected.add(new Detection(
                        AlertLevel.INFO, getContext().getString(R.string.mvt_dangerous_settings_title),
                        String.format(getContext().getString(R.string.mvt_dangerous_settings_message),
                            ds.description, entry.getKey(), entry.getValue()
                        )));
            }
        }
    }
}
