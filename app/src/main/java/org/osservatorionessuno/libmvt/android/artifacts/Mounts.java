package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.bugbane.R;
import org.osservatorionessuno.libmvt.common.AlertLevel;
import org.osservatorionessuno.libmvt.common.Detection;
import org.osservatorionessuno.libmvt.common.Indicators.IndicatorType;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Predicate;
import org.json.JSONArray;
import java.io.InputStream;
import java.io.IOException;

/**
 * Parser for the output of the `mount` command.
 */
public class Mounts extends AndroidArtifact {
    private static final Set<String> SUSPICIOUS_MOUNT_POINTS = Set.of("/system", "/vendor", "/product", "/system_ext");
    private static final Set<String> SUSPICIOUS_OPTIONS = Set.of("rw", "remount", "noatime", "nodiratime");
    private static final Set<String> ALLOWLIST_NOATIME = Set.of("/system_dlkm", "/system_ext", "/product", "/vendor", "/vendor_dlkm");

    @Override
    public List<String> paths() {
        return List.of("mounts.json");
    }

    @Override
    public void parse(InputStream input) throws IOException {
        // Expect input as a JSON string representing an array of mount entry lines (not direct file lines).
        String content = collectText(input);
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        try {
            JSONArray entries = new JSONArray(content);

            Predicate<String> isSuspicious = mnt -> {
                return SUSPICIOUS_MOUNT_POINTS.contains(mnt) || 
                       SUSPICIOUS_MOUNT_POINTS.stream().anyMatch(sp -> mnt.startsWith(sp));
            };

            for (int idx = 0; idx < entries.length(); idx++) {
                String entry = entries.getString(idx);
                if (entry == null || entry.trim().isEmpty()) {
                    continue;
                }

                String device = null;
                String mountPoint = null;
                String filesystemType = null;
                String mountOptions = "";

                if (!entry.contains(" on ") || !entry.contains(" type ")) {
                    // Skip lines that don't match the expected format
                    continue;
                }

                try {
                    String[] deviceSplit = entry.split(" on ", 2);
                    device = deviceSplit[0].trim();
                    String rest = deviceSplit[1];

                    String[] mountAndFsSplit = rest.split(" type ", 2);
                    mountPoint = mountAndFsSplit[0].trim();
                    String fsPart = mountAndFsSplit[1];

                    if (fsPart.contains("(") && fsPart.endsWith(")")) {
                        int parenIdx = fsPart.indexOf('(');
                        filesystemType = fsPart.substring(0, parenIdx).trim();
                        mountOptions = fsPart.substring(parenIdx + 1, fsPart.length() - 1).trim();
                    } else {
                        filesystemType = fsPart.trim();
                        mountOptions = "";
                    }

                    if (device.isEmpty() || mountPoint.isEmpty() || filesystemType.isEmpty()) {
                        continue;
                    }

                    String[] optionsArray = mountOptions.isEmpty() ? new String[0] : mountOptions.split(",");
                    List<String> optionsList = new ArrayList<>();
                    for (String opt : optionsArray) {
                        String trimmed = opt.trim();
                        if (!trimmed.isEmpty()) {
                            optionsList.add(trimmed);
                        }
                    }

                    boolean isSystemPartition = isSuspicious.test(mountPoint);
                    boolean isReadWrite = optionsList.contains("rw");

                    Map<String,Object> mountEntry = new HashMap<>();
                    mountEntry.put("device", device);
                    mountEntry.put("mount_point", mountPoint);
                    mountEntry.put("filesystem_type", filesystemType);
                    mountEntry.put("mount_options", mountOptions);
                    mountEntry.put("options_list", optionsList);
                    mountEntry.put("is_system_partition", isSystemPartition);
                    mountEntry.put("is_read_write", isReadWrite);

                    results.add(mountEntry);
                } catch (Exception e) {
                    // parsing failed, skip this line
                    continue;
                }
            }
        } catch (Exception ex) {
            // If malformed JSON or unexpected error - skip all
            return;
        }
        return;
    }

    @Override
    public void checkIndicators() {
        List<Map<String, Object>> systemRwMounts = new ArrayList<>();
        List<Map<String, Object>> suspiciousMounts = new ArrayList<>();

        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mount = (Map<String, Object>) obj;
            String mountPoint = (String) mount.get("mount_point");
            @SuppressWarnings("unchecked")
            List<String> options = (List<String>) mount.get("options_list");

            // Check for system partitions mounted as read-write
            if (Boolean.TRUE.equals(mount.get("is_system_partition")) && Boolean.TRUE.equals(mount.get("is_read_write"))) {
                systemRwMounts.add(mount);
                if ("/system".equals(mountPoint)) {
                    detected.add(new Detection(AlertLevel.HIGH, getContext().getString(R.string.mvt_mounts_root_title),
                        String.format(
                            getContext().getString(R.string.mvt_mounts_root_message), 
                            mountPoint
                        )));
                } else {
                    detected.add(new Detection(AlertLevel.HIGH, getContext().getString(R.string.mvt_mounts_system_title),
                        String.format(
                            getContext().getString(R.string.mvt_mounts_system_message), 
                            mountPoint
                        )));
                }
            }

            // Check for other suspicious mount options
            List<String> suspiciousOpts = new ArrayList<>();
            for (String opt : options) {
                if (SUSPICIOUS_OPTIONS.contains(opt)) {
                    suspiciousOpts.add(opt);
                }
            }
            if (!suspiciousOpts.isEmpty() && Boolean.TRUE.equals(mount.get("is_system_partition"))) {
                // ALLOWLIST_NOATIME handling: skip allowed case
                String mountOptions = (String) mount.get("mount_options");
                if (mountOptions != null && mountOptions.contains("noatime")
                    && ALLOWLIST_NOATIME.contains((String) mount.get("mount_point"))) {
                    continue;
                }
                suspiciousMounts.add(mount);
                detected.add(new Detection(AlertLevel.HIGH, getContext().getString(R.string.mvt_mounts_suspicious_title),
                    String.format(getContext().getString(R.string.mvt_mounts_suspicious_message), 
                        mountPoint, String.join(", ", suspiciousOpts)
                    )));
            }

            // Log interesting mount information (just log - map to LOG detection)
            if ("/data".equals(mountPoint) || mountPoint.startsWith("/sdcard")) {
                detected.add(new Detection(AlertLevel.LOG, getContext().getString(R.string.mvt_mounts_data_title),
                    String.format(getContext().getString(R.string.mvt_mounts_data_message),
                        mountPoint,
                        mount.get("filesystem_type"),
                        mount.get("mount_options")
                    )));
            }
        }

        // Check indicators if available
        if (indicators == null) return;

        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mount = (Map<String, Object>) obj;

            // Check if any mount points match indicators
            String mp = (String) mount.get("mount_point");
            detected.addAll(indicators.matchString(mp, IndicatorType.FILE_PATH));

            // Check device paths for indicators
            String dev = (String) mount.get("device");
            detected.addAll(indicators.matchString(dev, IndicatorType.FILE_PATH));
        }
    }
}
