package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.AlertLevel;
import org.osservatorionessuno.libmvt.common.IndicatorType;
import org.osservatorionessuno.libmvt.common.Detection;

import java.util.*;

/** Parser for dumpsys appops output. */
public class DumpsysAppops extends AndroidArtifact {
    private static final Set<String> RISKY_PERMISSIONS = Set.of("REQUEST_INSTALL_PACKAGES");
    private static final Set<String> RISKY_PACKAGES = Set.of("com.android.shell");

    @Override
    public void parse(String output) {
        results.clear();
        Map<String, Object> pkg = null;
        Map<String, Object> perm = new HashMap<>();
        Map<String, Object> entry = new HashMap<>();
        String uid = null;
        boolean inPackages = false;
        for (String line : output.split("\n")) {
            if (line.startsWith("  Uid 0:")) inPackages = true;
            if (!inPackages) continue;
            if (line.startsWith("  Uid ")) {
                uid = line.substring(6, line.length() - 1);
                if (!entry.isEmpty()) { addEntry(perm, entry); entry = new HashMap<>(); }
                if (pkg != null) {
                    finishPerm(pkg, perm);
                    results.add(pkg);
                }
                pkg = null;
                perm = new HashMap<>();
                continue;
            }
            if (line.startsWith("    Package ")) {
                if (!entry.isEmpty()) { addEntry(perm, entry); entry = new HashMap<>(); }
                if (pkg != null) {
                    finishPerm(pkg, perm);
                    results.add(pkg);
                }
                pkg = new HashMap<>();
                pkg.put("package_name", line.substring(12, line.length() - 1));
                pkg.put("permissions", new ArrayList<>());
                pkg.put("uid", uid);
                perm = new HashMap<>();
                continue;
            }
            if (pkg != null && line.startsWith("      ") && line.length() > 6 && line.charAt(6) != ' ') {
                if (!entry.isEmpty()) { addEntry(perm, entry); entry = new HashMap<>(); }
                finishPerm(pkg, perm);
                perm = new HashMap<>();
                String[] parts = line.trim().split("\\s+");
                perm.put("name", parts[0]);
                perm.put("entries", new ArrayList<>());
                if (parts.length > 1) perm.put("access", parts[1].substring(1, parts[1].length()-1));
                continue;
            }
            if (line.startsWith("          ")) {
                String access = line.split(":")[0].trim();
                if (!access.equals("Access") && !access.equals("Reject")) continue;
                if (!entry.isEmpty()) { addEntry(perm, entry); entry = new HashMap<>(); }
                entry.put("access", access);
                int l = line.indexOf('['); int r = line.indexOf(']');
                if (l > 0 && r > l) entry.put("type", line.substring(l+1,r));
                int lp = line.indexOf(']', r)+1;
                int lp2 = line.indexOf('(', lp);
                if (lp > 0 && lp2 > lp) {
                    String ts = line.substring(lp, lp2).trim();
                    entry.put("timestamp", ts); // keep as string
                }
                continue;
            }
            if (line.trim().isEmpty()) break;
        }
        if (!entry.isEmpty()) addEntry(perm, entry);
        finishPerm(pkg, perm);
        if (pkg != null) results.add(pkg);
    }

    @SuppressWarnings("unchecked")
    private void addEntry(Map<String, Object> perm, Map<String, Object> entry) {
        ((List<Object>) perm.computeIfAbsent("entries", k -> new ArrayList<>())).add(entry);
    }

    @SuppressWarnings("unchecked")
    private void finishPerm(Map<String, Object> pkg, Map<String, Object> perm) {
        if (pkg != null && !perm.isEmpty()) {
            ((List<Object>) pkg.get("permissions")).add(perm);
        }
    }

    @Override
    public void checkIndicators() {
        if (indicators == null) return;
        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;
            String pkgName = (String) map.get("package_name");
            detected.addAll(indicators.matchString(pkgName, IndicatorType.APP_ID));
            
            boolean riskyPkg = RISKY_PACKAGES.contains(pkgName);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> perms = (List<Map<String, Object>>) map.get("permissions");
            if (perms == null) continue;
            for (Map<String, Object> perm : perms) {
                String permName = (String) perm.get("name");
                if (RISKY_PERMISSIONS.contains(permName) || riskyPkg) {
                    detected.add(new Detection(AlertLevel.MEDIUM, IndicatorType.PROCESS, permName, pkgName));
                }
            }
        }
    }
}
