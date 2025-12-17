package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.AlertLevel;
import org.osservatorionessuno.libmvt.common.Detection;
import org.osservatorionessuno.libmvt.common.IndicatorType;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Predicate;
import org.json.JSONArray;

/**
 * TODO
 */
public class Mounts extends AndroidArtifact {
    private static final Set<String> SUSPICIOUS_MOUNT_POINTS = Set.of("/system", "/vendor", "/product", "/system_ext");
    private static final Set<String> SUSPICIOUS_OPTIONS = Set.of("rw", "remount", "noatime", "nodiratime");
    private static final Set<String> ALLOWLIST_NOATIME = Set.of("/system_dlkm", "/system_ext", "/product", "/vendor", "/vendor_dlkm");

    @Override
    public void parse(String input) {
        // Expect input as a JSON string representing an array of mount entry lines (not direct file lines).
        if (input == null || input.trim().isEmpty()) {
            return;
        }
        try {
            JSONArray entries = new JSONArray(input);

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

                    this.results.add(mountEntry);
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

        for (Object obj : this.results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mount = (Map<String, Object>) obj;
            String mountPoint = (String) mount.get("mount_point");
            @SuppressWarnings("unchecked")
            List<String> options = (List<String>) mount.get("options_list");

            // Check for system partitions mounted as read-write
            if (Boolean.TRUE.equals(mount.get("is_system_partition")) && Boolean.TRUE.equals(mount.get("is_read_write"))) {
                systemRwMounts.add(mount);
                if ("/system".equals(mountPoint)) {
                    detected.add(new Detection(AlertLevel.HIGH, IndicatorType.OTHER,
                        "Root detected /system partition is mounted as read-write (rw)", mountPoint));
                } else {
                    detected.add(new Detection(AlertLevel.HIGH, IndicatorType.OTHER,
                        String.format("System partition %s is mounted as read-write (rw). This may indicate system modifications.", mountPoint),
                        mountPoint));
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
                detected.add(new Detection(AlertLevel.HIGH, IndicatorType.OTHER,
                    String.format("Suspicious mount options found for %s: %s",
                                  mountPoint, String.join(", ", suspiciousOpts)),
                    mountPoint));
            }

            // Log interesting mount information (just log - map to LOG detection)
            if ("/data".equals(mountPoint) || mountPoint.startsWith("/sdcard")) {
                detected.add(new Detection(AlertLevel.LOG, IndicatorType.OTHER,
                    String.format("Data partition: %s mounted as %s with options: %s",
                                  mountPoint,
                                  mount.get("filesystem_type"),
                                  mount.get("mount_options")),
                    mountPoint));
            }
        }

        // Check indicators if available
        if (this.indicators == null) {
            return;
        }

        return;

        /*for (Object obj : this.results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mount = (Map<String, Object>) obj;
            String mp = (String) mount.get("mount_point");
            String dev = (String) mount.get("device");

            // Check if any mount points match indicators
            List<Detection> mountPointMatches = this.indicators.matchFilePath(mp, IndicatorType.PATH);
            for (Detection d : mountPointMatches) {
                d.level = AlertLevel.CRITICAL;
                d.details = String.format("Mount point matches indicator: %s", mp);
                d.relatedObject = mount;
                detected.add(d);
            }

            // Check device paths for indicators
            List<Detection> devMatches = this.indicators.matchFilePath(dev, IndicatorType.PATH);
            for (Detection d : devMatches) {
                d.level = AlertLevel.CRITICAL;
                d.details = String.format("Device path matches indicator: %s", dev);
                d.relatedObject = mount;
                detected.add(d);
            }
        }*/
    }
}
