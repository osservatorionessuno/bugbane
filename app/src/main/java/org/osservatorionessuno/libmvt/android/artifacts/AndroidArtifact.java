package org.osservatorionessuno.libmvt.android.artifacts;

import android.content.Context;
import org.osservatorionessuno.libmvt.common.Artifact;

/**
 * Base class for Android-related artifact parsers.
 * Still pure Java, only operates on Strings.
 */
public abstract class AndroidArtifact extends Artifact {
    protected Context context;

    /**
     * Set the Android Context for accessing resources (e.g., string resources).
     */
    public void setContext(Context context) {
        this.context = context;
    }

    /**
     * Extract a section from a dumpsys string by a separator, stopping at a line starting with '---'.
     */
    protected static String extractDumpsysSection(String dumpsys, String separator) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        boolean inSection = false;
        final String delimiter = "-".repeat(78);
        for (String line : dumpsys.split("\\R")) {
            if (line.trim().equals(separator)) {
                inSection = true;
                continue;
            }
            if (!inSection) {
                continue;
            }
            if (line.trim().startsWith(delimiter)) {
                break;
            }
            lines.add(line);
        }
        return String.join("\n", lines);
    }
}
