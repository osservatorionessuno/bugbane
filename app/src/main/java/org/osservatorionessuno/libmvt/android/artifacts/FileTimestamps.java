package org.osservatorionessuno.libmvt.android.artifacts;

import java.util.*;

/** Utility artifact for converting file timestamp records to timeline entries. */
public class FileTimestamps extends AndroidArtifact {
    @Override
    public void parse(String input) {
        // No parsing implemented; timestamps are expected as structured records.
    }

    @Override
    public void checkIndicators() {
        // No IOC matching for this artifact.
    }

    /** Serialize a record with access/modified/changed times to timeline entries. */
    public List<Map<String, Object>> serialize(Map<String, Object> record) {
        List<Map<String, Object>> list = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        Object at = record.get("access_time");
        Object ct = record.get("changed_time");
        Object mt = record.get("modified_time");
        List<Object> times = Arrays.asList(at, ct, mt);
        for (Object t : times) {
            if (t == null || seen.contains(t)) continue;
            seen.add(t);
            String macb = "";
            macb += Objects.equals(t, mt) ? "M" : "-";
            macb += Objects.equals(t, at) ? "A" : "-";
            macb += Objects.equals(t, ct) ? "C" : "-";
            macb += "-";
            String msg = (String) record.get("path");
            if (record.get("context") != null && !((String)record.get("context")).isEmpty()) {
                msg += " (" + record.get("context") + ")";
            }
            Map<String, Object> m = new HashMap<>();
            m.put("timestamp", t);
            m.put("module", this.getClass().getSimpleName());
            m.put("event", macb);
            m.put("data", msg);
            list.add(m);
        }
        return list;
    }
}
