package org.osservatorionessuno.libmvt.android.artifacts;

import org.osservatorionessuno.bugbane.R;
import org.osservatorionessuno.libmvt.common.AlertLevel;
import org.osservatorionessuno.libmvt.common.Detection;
import org.osservatorionessuno.libmvt.common.Indicators.IndicatorType;
import org.osservatorionessuno.libmvt.common.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.io.InputStream;

/**
 * Parser for packages.json artifact collected by the `packages` module.
 * This is different from the DumpsysPackages module that use a BugReport artifact instead.
 */
public class Packages extends AndroidArtifact {

    @Override
    public List<String> paths() {
        return List.of("packages.json");
    }

    private static class PackageResult {
        String name = "";
        Boolean disabled = false;
        String installer = "";
        Integer uid = null;
        Boolean system = false;
        Boolean thirdParty = false;
        List<Map<String, String>> files = new ArrayList<>();
    }

    @Override
    public void parse(InputStream input) throws IOException {
        try {
            // Try to parse the input as a JSON array
            JSONArray arr = new JSONArray(collectText(input));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);

                PackageResult result = new PackageResult();
                result.name = obj.getString("name");
                result.disabled = obj.getBoolean("disabled");
                result.installer = obj.getString("installer");
                result.uid = obj.getInt("uid");
                result.system = obj.getBoolean("system");
                result.thirdParty = obj.getBoolean("third_party");
                
                JSONArray filesArray = obj.getJSONArray("files");
                for (int j = 0; j < filesArray.length(); j++) {
                    JSONObject fileObj = filesArray.getJSONObject(j);
                    result.files.add(
                        Map.of(
                            "path", fileObj.getString("path"),
                            "local_name", fileObj.getString("local_name"),
                            "md5", fileObj.getString("md5"),
                            "sha1", fileObj.getString("sha1"),
                            "sha256", fileObj.getString("sha256"),
                            "sha512", fileObj.getString("sha512"),
                            "certificate_md5", fileObj.getJSONObject("certificate").getString("Md5"),
                            "certificate_sha1", fileObj.getJSONObject("certificate").getString("Sha1"),
                            "certificate_sha256", fileObj.getJSONObject("certificate").getString("Sha256")
                        )
                    );
                }
                results.add(result);
            }
        } catch (JSONException ex) {
            // TODO: Something went wrong
        }
    }

    @Override
    public void checkIndicators() {
        for (Object r : results) {
            PackageResult result = (PackageResult) r;

            if (Utils.ROOT_PACKAGES.contains(result.name)) {
                detected.add(new Detection(AlertLevel.MEDIUM, getContext().getString(R.string.mvt_packages_root_package_title),
                    String.format(
                        getContext().getString(R.string.mvt_packages_root_package_message),
                        result.name
                    )));
                continue;
            }

            if ("null".equals(result.installer) && !result.system) {
                detected.add(new Detection(AlertLevel.HIGH, getContext().getString(R.string.mvt_packages_non_system_package_title),
                    String.format(
                        getContext().getString(R.string.mvt_packages_non_system_package_message),
                        result.name
                    )));
            } else if (Utils.THIRD_PARTY_STORE_INSTALLERS.contains(result.installer)) {
                detected.add(new Detection(AlertLevel.INFO, getContext().getString(R.string.mvt_packages_third_party_store_package_title),
                    String.format(
                        getContext().getString(R.string.mvt_packages_third_party_store_package_message),
                        result.installer,
                        result.name
                    )));
            } else if (Utils.BROWSER_INSTALLERS.contains(result.installer)) {
                detected.add(new Detection(AlertLevel.MEDIUM, getContext().getString(R.string.mvt_packages_browser_package_title),
                    String.format(
                        getContext().getString(R.string.mvt_packages_browser_package_message),
                        result.installer,
                        result.name
                    )));
            }


            if (Utils.SECURITY_PACKAGES.contains(result.name) && result.disabled) {
                detected.add(new Detection(AlertLevel.MEDIUM, getContext().getString(R.string.mvt_packages_security_package_title),
                    String.format(
                        getContext().getString(R.string.mvt_packages_security_package_message),
                        result.name
                    )));
            }

            if (Utils.SYSTEM_UPDATE_PACKAGES.contains(result.name) && result.disabled) {
                detected.add(new Detection(AlertLevel.MEDIUM, getContext().getString(R.string.mvt_packages_system_update_package_title),
                    String.format(
                        getContext().getString(R.string.mvt_packages_system_update_package_message),
                        result.name
                    )));
            }

            if (indicators == null) return;

            detected.addAll(indicators.matchString(result.name, IndicatorType.APP_ID));

            for (Map<String, String> packageFile : result.files) {
                detected.addAll(indicators.matchString(packageFile.get("path"), IndicatorType.FILE_PATH));
                detected.addAll(indicators.matchString(packageFile.get("sha256"), IndicatorType.FILE_HASH_SHA256));

                // TODO: Implement certificate checks
            }
        }
    }
}
