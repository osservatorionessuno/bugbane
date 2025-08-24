package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.libmvt.common.IndicatorType;
import org.osservatorionessuno.libmvt.common.Detection;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for dumpsys package information.
 */
public class DumpsysPackages extends AndroidArtifact {
    private static final Set<String> ROOT_PACKAGES = Set.of(
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.koushikdutta.rommanager",
            "com.koushikdutta.rommanager.license",
            "com.dimonvideo.luckypatcher",
            "com.chelpus.lackypatch",
            "com.ramdroid.appquarantine",
            "com.ramdroid.appquarantinepro",
            "com.devadvance.rootcloak",
            "com.devadvance.rootcloakplus",
            "de.robv.android.xposed.installer",
            "com.saurik.substrate",
            "com.zachspong.temprootremovejb",
            "com.amphoras.hidemyroot",
            "com.amphoras.hidemyrootadfree",
            "com.formyhm.hiderootPremium",
            "com.formyhm.hideroot",
            "me.phh.superuser",
            "eu.chainfire.supersu.pro",
            "com.kingouser.com",
            "com.topjohnwu.magisk"
    );

    private static class PackageDetails {
        String packageName = "";
        String uid = "";
        String versionName = "";
        String versionCode = "";
        String timestamp = "";
        String firstInstall = "";
        String lastUpdate = "";
        List<Map<String, Object>> permissions = new ArrayList<>();
        List<String> requestedPermissions = new ArrayList<>();
    }

    private static Map<String, Object> toMap(PackageDetails d) {
        Map<String, Object> m = new HashMap<>();
        m.put("package_name", d.packageName);
        m.put("uid", d.uid);
        m.put("version_name", d.versionName);
        m.put("version_code", d.versionCode);
        m.put("timestamp", d.timestamp);
        m.put("first_install_time", d.firstInstall);
        m.put("last_update_time", d.lastUpdate);
        m.put("permissions", d.permissions);
        m.put("requested_permissions", d.requestedPermissions);
        return m;
    }

    static PackageDetails parsePackageBlock(List<String> lines) {
        PackageDetails d = new PackageDetails();
        boolean inInstall = false, inRuntime = false, inDeclared = false, inRequested = true;
        for (String line : lines) {
            if (inInstall) {
                if (line.startsWith("    ") && !line.startsWith("      ")) {
                    inInstall = false;
                } else {
                    String[] lineinfo = line.trim().split(":", 2);
                    String permission = lineinfo[0];
                    Boolean granted = null;
                    if (lineinfo.length > 1 && lineinfo[1].contains("granted=")) {
                        granted = lineinfo[1].contains("granted=true");
                    }
                    Map<String, Object> p = new HashMap<>();
                    p.put("name", permission);
                    p.put("granted", granted);
                    p.put("type", "install");
                    d.permissions.add(p);
                    continue;
                }
            }
            if (inRuntime) {
                if (!line.startsWith("        ")) {
                    inRuntime = false;
                } else {
                    String[] lineinfo = line.trim().split(":", 2);
                    String permission = lineinfo[0];
                    Boolean granted = null;
                    if (lineinfo.length > 1 && lineinfo[1].contains("granted=")) {
                        granted = lineinfo[1].contains("granted=true");
                    }
                    Map<String, Object> p = new HashMap<>();
                    p.put("name", permission);
                    p.put("granted", granted);
                    p.put("type", "runtime");
                    d.permissions.add(p);
                    continue;
                }
            }
            if (inDeclared) {
                if (!line.startsWith("      ")) {
                    inDeclared = false;
                } else {
                    String permission = line.trim().split(":")[0];
                    Map<String, Object> p = new HashMap<>();
                    p.put("name", permission);
                    p.put("type", "declared");
                    d.permissions.add(p);
                    continue;
                }
            }
            if (inRequested) {
                if (!line.startsWith("      ")) {
                    inRequested = false;
                } else {
                    d.requestedPermissions.add(line.trim());
                    continue;
                }
            }
            line = line.trim();
            if (line.startsWith("userId=")) d.uid = line.substring(7).trim();
            else if (line.startsWith("versionName=")) d.versionName = line.substring(12).trim();
            else if (line.startsWith("versionCode=")) d.versionCode = line.substring(12).trim();
            else if (line.startsWith("timeStamp=")) d.timestamp = line.substring(10).trim();
            else if (line.startsWith("firstInstallTime=")) d.firstInstall = line.substring(17).trim();
            else if (line.startsWith("lastUpdateTime=")) d.lastUpdate = line.substring(15).trim();
            else if (line.equals("install permissions:")) inInstall = true;
            else if (line.equals("runtime permissions:")) inRuntime = true;
            else if (line.equals("declared permissions:")) inDeclared = true;
            else if (line.equals("requested permissions:")) inRequested = true;
        }
        return d;
    }

    List<Map<String, Object>> parsePackages(String output) {
        Pattern pkgRx = Pattern.compile("  Package \\[(.+?)\\].*");
        List<Map<String, Object>> result = new ArrayList<>();
        String packageName = null;
        List<String> lines = new ArrayList<>();
        Map<String, Object> current = new HashMap<>();
        for (String line : output.split("\n")) {
            if (line.startsWith("  Package [")) {
                if (!lines.isEmpty()) {
                    PackageDetails d = parsePackageBlock(lines);
                    d.packageName = packageName;
                    current.putAll(toMap(d));
                    result.add(current);
                }
                lines = new ArrayList<>();
                current = new HashMap<>();
                Matcher m = pkgRx.matcher(line);
                if (m.find()) {
                    packageName = m.group(1);
                    current.put("package_name", packageName);
                } else {
                    packageName = null;
                }
                continue;
            }
            if (packageName == null) continue;
            lines.add(line);
        }
       if (!lines.isEmpty()) {
            PackageDetails d = parsePackageBlock(lines);
            d.packageName = packageName;
            current.putAll(toMap(d));
            result.add(current);
       }
        return result;
    }

    @Override
    public void parse(String content) {
        results.clear();
        boolean inPackageList = false;
        List<String> packageLines = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (line.startsWith("Packages:")) { inPackageList = true; continue; }
            if (!inPackageList) continue;
            if (line.trim().isEmpty()) break;
            packageLines.add(line);
        }
        results.addAll(parsePackages(String.join("\n", packageLines)));
    }

    @Override
    public void checkIndicators() {
        for (Object obj : results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> record = (Map<String, Object>) obj;
            String pkg = (String) record.get("package_name");
            if (ROOT_PACKAGES.contains(pkg)) {
                detected.add(new Detection(IndicatorType.PROCESS, pkg, "root_package"));
            }
            if (indicators != null) {
                detected.addAll(indicators.matchString(pkg, IndicatorType.APP_ID));
            }
        }
    }
}
