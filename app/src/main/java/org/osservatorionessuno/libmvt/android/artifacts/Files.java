package org.osservatorionessuno.libmvt.android.artifacts;

import org.json.JSONException;
import org.osservatorionessuno.bugbane.R;
import org.osservatorionessuno.libmvt.common.AlertLevel;
import org.osservatorionessuno.libmvt.common.Detection;
import org.osservatorionessuno.libmvt.common.IndicatorType;

import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * TODO
 */
public class Files extends AndroidArtifact {
    private static final Set<String> SUSPICIOUS_PATHS = Set.of(
        "/data/local/tmp/"
    );

    @Override
    public void parse(String input) {
        try {
            // Try to parse the input as a JSON array
            JSONArray arr = new JSONArray(input);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Map<String, Object> map = new HashMap<>();
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    map.put(key, obj.get(key));
                }
                results.add(map);
            }
        } catch (JSONException ex) {
            // Fallback: input may be JSON lines, one object per line
            String[] lines = input.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    JSONObject obj = new JSONObject(trimmed);
                    Map<String, Object> map = new HashMap<>();
                    Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        map.put(key, obj.get(key));
                    }
                    // Again, no conversion of timestamps; assume preprocessed
                    results.add(map);
                } catch (JSONException e2) {
                    // skip invalid lines
                    // TODO: maybe report a better error message (?)
                }
            }
        }
    }

    @Override
    public void checkIndicators() {
        if (indicators == null) {
            return;
        }

        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> file = (Map<String, Object>) obj;

            String path = Objects.toString(file.get("path"), "");
            if (path.isEmpty()) continue;

            if (detected.addAll(indicators.matchString(path, IndicatorType.FILE_PATH))) {
                continue; // if any indicator matches, skip the rest
            }

            for (String suspicious : SUSPICIOUS_PATHS) {
                if (path.startsWith(suspicious)) {
                    String fileType = "";

                    // Determine if the file is executable (Unix mode bits)
                    Object modeVal = file.get("mode");
                    long mode = 0;
                    if (modeVal instanceof Number) {
                        mode = ((Number) modeVal).longValue();
                    } else if (modeVal instanceof String) {
                        try {
                            mode = Long.decode((String) modeVal);
                        } catch (NumberFormatException nfe) {
                            // ignore
                        }
                    }
                    // executable for owner, group, or others (octal 0100, 0010, 0001)
                    if ((mode & 0x49) != 0) { // (S_IXUSR | S_IXGRP | S_IXOTH)
                        fileType = "executable ";
                    }

                    String msg = String.format(context.getString(R.string.mvt_files_suspicious_path_message), fileType, path);
                    detected.add(new Detection(AlertLevel.HIGH, context.getString(R.string.mvt_files_suspicious_path_title), msg));
                }
            }

            /*
            Object sha256Obj = file.get("sha256");
            String sha256 = (sha256Obj != null) ? sha256Obj.toString() : "";
            if (sha256.isEmpty()) continue;

            // Check if file hash matches any indicator
            // (Python: ioc_match = self.indicators.check_file_hash(result.get("sha256")))
            var hashMatches = indicators.matchFileHash(sha256, IndicatorType.HASH);
            if (!hashMatches.isEmpty()) {
                for (Detection det : hashMatches) {
                    det.level = AlertLevel.CRITICAL;
                    det.relatedObject = file;
                    detected.add(det);
                }
            }*/

            // TODO: add SHA1 and MD5 check when available
        }
    }
}
