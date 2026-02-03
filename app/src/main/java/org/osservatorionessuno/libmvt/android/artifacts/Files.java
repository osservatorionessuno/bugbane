package org.osservatorionessuno.libmvt.android.artifacts;

import org.json.JSONException;
import org.osservatorionessuno.bugbane.R;
import org.osservatorionessuno.libmvt.common.AlertLevel;
import org.osservatorionessuno.libmvt.common.Detection;
import org.osservatorionessuno.libmvt.common.Indicators.IndicatorType;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Parser for the list of all the files available on the devices
 */
public class Files extends AndroidArtifact {
    private static final Set<String> SUSPICIOUS_PATHS = Set.of(
        "/data/local/tmp/"
    );

    @Override
    public List<String> paths() {
        return List.of("files.json");
    }

    @Override
    public void parse(InputStream input) throws IOException {
        try {
            // Try to parse the input as a JSON array
            JSONArray arr = new JSONArray(collectText(input));
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
            // TODO: I think this wont work cause Text was already collected.
            for (String line : collectLines(input)) {
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
        if (indicators == null) return;

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
                            mode = Long.decode("0" + (String) modeVal);
                        } catch (NumberFormatException nfe) {
                            // ignore
                        }
                    }
                    // executable for owner, group, or others (octal 0100, 0010, 0001)
                    if ((mode & 0111) != 0) { // (S_IXUSR | S_IXGRP | S_IXOTH)
                        fileType = "executable ";
                    }

                    String msg = String.format(getContext().getString(R.string.mvt_files_suspicious_path_message), fileType, path);
                    detected.add(new Detection(AlertLevel.HIGH, getContext().getString(R.string.mvt_files_suspicious_path_title), msg));
                }
            }
 
            Object sha256Obj = file.get("sha256");
            String sha256 = (sha256Obj != null) ? sha256Obj.toString() : "";
            if (sha256.isEmpty()) continue;

            // Check if file hash matches any indicator
            detected.addAll(indicators.matchString(sha256, IndicatorType.FILE_HASH_SHA256));

            // TODO: add SHA1 and MD5 check when available
        }
    }
}
