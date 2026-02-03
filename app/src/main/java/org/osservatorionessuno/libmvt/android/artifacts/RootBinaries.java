package org.osservatorionessuno.libmvt.android.artifacts;

import org.json.JSONArray;
import org.json.JSONException;
import org.osservatorionessuno.libmvt.common.AlertLevel;
import org.osservatorionessuno.libmvt.common.Detection;
import org.osservatorionessuno.libmvt.common.Indicators.IndicatorType;
import org.osservatorionessuno.libmvt.common.Utils;
import org.osservatorionessuno.bugbane.R;

import java.util.List;
import java.util.Map;
import java.io.InputStream;
import java.io.IOException;

/**
 * TODO
 */
public class RootBinaries extends AndroidArtifact {
    @Override
    public List<String> paths() {
        return List.of("root_binaries.json");
    }

    @Override
    public void parse(InputStream input) throws IOException, JSONException {
        results.clear();
        String content = collectText(input);
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        JSONArray entries = new JSONArray(content);
        for (int idx = 0; idx < entries.length(); idx++) {
            results.add(entries.getString(idx));
        }
    }

    @Override
    public void checkIndicators() {
        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            String path = (String) obj;
            if (path == null || path.trim().isEmpty()) continue;

            // Extract binary name from path
            String[] parts = path.replace("\\", "/").split("/");
            String binaryName = parts[parts.length - 1].toLowerCase();

            // If a description is found, than the binary is known, otherwise it is unknown.
            String description = Utils.ROOT_BINARIES.get(binaryName.toLowerCase());
            if (description != null) {
                detected.add(new Detection(AlertLevel.HIGH, getContext().getString(R.string.mvt_root_binaries_title),
                    String.format(
                        getContext().getString(R.string.mvt_root_binaries_message),
                        description,
                        path
                    )));
            } else {
                detected.add(new Detection(AlertLevel.HIGH, getContext().getString(R.string.mvt_root_binaries_title),
                    String.format(
                        getContext().getString(R.string.mvt_root_binaries_message),
                        "unknown root file",
                        path
                    )));
            }
        }
    }
}
