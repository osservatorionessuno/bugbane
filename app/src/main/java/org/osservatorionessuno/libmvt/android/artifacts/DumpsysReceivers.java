package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.IndicatorType;

import java.util.*;

/** Parser for dumpsys receivers information. */
public class DumpsysReceivers extends AndroidArtifact {
    @Override
    public void parse(String input) {
        results.clear();
        if (input == null) return;
        boolean inTable = false;
        boolean inNonData = false;
        String currentIntent = null;
        for (String line : input.split("\n")) {
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
            // TODO: Implement this
            /*
            List<String> receivers = (List<String>) map.get("receivers");
            for (String receiver : receivers) {
                if (receiver.equals("android.provider.Telephony.NEW_OUTGOING_SMS")) {
                    detected.add(new Detection(AlertLevel.LOG, IndicatorType.PROCESS, receiver, "intercept_outgoing_sms"));
                } else if (receiver.equals("android.provider.Telephony.SMS_RECEIVED")) {
                    detected.add(new Detection(AlertLevel.LOG, IndicatorType.PROCESS, receiver, "intercept_incoming_sms"));
                } else if (receiver.equals("android.intent.action.DATA_SMS_RECEIVED")) {
                    detected.add(new Detection(AlertLevel.LOG, IndicatorType.PROCESS, receiver, "intercept_data_sms"));
                } else if (receiver.equals("android.intent.action.PHONE_STATE")) {
                    detected.add(new Detection(AlertLevel.LOG, IndicatorType.PROCESS, receiver, "intercept_phone_state"));
                } else if (receiver.equals("android.intent.action.NEW_OUTGOING_CALL")) {
                    detected.add(new Detection(AlertLevel.LOG, IndicatorType.PROCESS, receiver, "intercept_outgoing_call"));
                }
                if (indicators == null) continue;
                String pkg = map.get("package_name");
                detected.addAll(indicators.matchString(pkg, IndicatorType.APP_ID));
            }
            */
        }
    }
}
