package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.bugbane.R;
import org.osservatorionessuno.libmvt.common.AlertLevel;
import org.osservatorionessuno.libmvt.common.Indicators.IndicatorType;
import org.osservatorionessuno.libmvt.common.Detection;

import java.util.*;
import java.io.IOException;
import java.io.InputStream;

/** Parser for dumpsys receivers information. */
public class DumpsysReceivers extends AndroidArtifact {

    @Override
    public List<String> paths() {
        return List.of("dumpsys.txt");
    }

    @Override
    public void parse(InputStream input) throws IOException {
        results.clear();
        if (input == null) return;
        boolean inTable = false;
        boolean inNonData = false;
        String currentIntent = null;
        for (String line : collectLines(input)) {
            if (line.startsWith("Receiver Resolver Table:")) { inTable = true; continue; }
            if (!inTable) continue;
            if (line.startsWith("  Non-Data Actions:")) { inNonData = true; continue; }
            if (!inNonData) continue;
            if (line.trim().isEmpty()) break;
            if (line.startsWith("     ") && !line.startsWith("        ") && line.contains(":")) {
                currentIntent = line.trim().replace(":", "");
                continue;
            }
            if (currentIntent == null) continue;
            if (!line.startsWith("        ")) { currentIntent = null; continue; }
            String receiver = line.trim().split(" ")[1];
            String pkg = receiver.split("/")[0];
            Map<String, String> rec = new HashMap<>();
            rec.put("intent", currentIntent);
            rec.put("package_name", pkg);
            rec.put("receiver", receiver);
            results.add(rec);
        }
    }

    @Override
    public void checkIndicators() {
        if (results.isEmpty()) return;

        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, String> map = (Map<String, String>) obj;

            String intent = map.get("intent");
            switch (Objects.requireNonNull(intent)) {
                case "android.provider.Telephony.NEW_OUTGOING_SMS":
                    detected.add(new Detection(AlertLevel.LOG, getContext().getString(R.string.mvt_dumpsys_receivers_intercept_outgoing_sms_title), intent));
                    break;
                case "android.provider.Telephony.SMS_RECEIVED":
                    detected.add(new Detection(AlertLevel.LOG, getContext().getString(R.string.mvt_dumpsys_receivers_intercept_incoming_sms_title), intent));
                    break;
                case "android.intent.action.DATA_SMS_RECEIVED":
                    detected.add(new Detection(AlertLevel.LOG, getContext().getString(R.string.mvt_dumpsys_receivers_intercept_data_sms_title), intent));
                    break;
                case "android.intent.action.PHONE_STATE":
                    detected.add(new Detection(AlertLevel.LOG, getContext().getString(R.string.mvt_dumpsys_receivers_intercept_phone_state_title), intent));
                    break;
                case "android.intent.action.NEW_OUTGOING_CALL":
                    detected.add(new Detection(AlertLevel.LOG, getContext().getString(R.string.mvt_dumpsys_receivers_intercept_outgoing_call_title), intent));
                    break;
            }

            if (indicators == null) continue;
            String pkg = map.get("package_name");
            detected.addAll(indicators.matchString(pkg, IndicatorType.APP_ID));
        }
    }
}
