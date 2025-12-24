package org.osservatorionessuno.libmvt.common;

import org.osservatorionessuno.bugbane.R;
import android.content.Context;
import android.util.Log;

import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads indicators from .json and .stix2 (JSON) files and matches strings.
 * JSON parsing is done with Android's built-in org.json (no extra deps).
 * Keyword matching uses Aho-Corasick tries for efficient pattern matching.
 */
public class Indicators {
    private static final String TAG = "Indicators";

    /**
     * Configuration mapping indicator types to their JSON field keys and STIX pattern keys.
     * This makes it easy to add new indicator types without modifying the core logic.
     */
    private static final Map<IndicatorType, Set<String>> INDICATOR_CONFIG = new EnumMap<>(IndicatorType.class);
    
    static {
        INDICATOR_CONFIG.put(IndicatorType.DOMAIN, Set.of("domain-name:value", "ipv4-addr:value"));
        INDICATOR_CONFIG.put(IndicatorType.URL, Set.of("url:value"));
        INDICATOR_CONFIG.put(IndicatorType.PROCESS, Set.of("process:name"));
        INDICATOR_CONFIG.put(IndicatorType.EMAIL, Set.of("email-addr:value"));
        INDICATOR_CONFIG.put(IndicatorType.APP_ID, Set.of("app:id"));
        INDICATOR_CONFIG.put(IndicatorType.PROPERTY, Set.of("android-property:name"));
        INDICATOR_CONFIG.put(IndicatorType.FILE_PATH, Set.of("file:path"));
        INDICATOR_CONFIG.put(IndicatorType.FILE_NAME, Set.of("file:name"));
        INDICATOR_CONFIG.put(IndicatorType.FILE_HASH_MD5, Set.of("file:hashes.md5"));
        INDICATOR_CONFIG.put(IndicatorType.FILE_HASH_SHA1, Set.of("file:hashes.sha1"));
        INDICATOR_CONFIG.put(IndicatorType.FILE_HASH_SHA256, Set.of("file:hashes.sha256"));
        INDICATOR_CONFIG.put(IndicatorType.APP_CERT_HASH_MD5, Set.of("app:cert.md5"));
        INDICATOR_CONFIG.put(IndicatorType.APP_CERT_HASH_SHA1, Set.of("app:cert.sha1"));
        INDICATOR_CONFIG.put(IndicatorType.APP_CERT_HASH_SHA256, Set.of("app:cert.sha256"));
        INDICATOR_CONFIG.put(IndicatorType.IOS_PROFILE_ID, Set.of("configuration-profile:id"));
    }

    private final Map<IndicatorType, Trie.TrieBuilder> trieBuilders;
    private final Map<IndicatorType, Trie> tries;
    private Context context;

    public Indicators() {
        this.trieBuilders = new EnumMap<>(IndicatorType.class);
        this.tries = new EnumMap<>(IndicatorType.class);
        
        // Initialize builders for all configured indicator types
        for (IndicatorType type : INDICATOR_CONFIG.keySet()) {
            trieBuilders.put(type, Trie.builder().ignoreCase());
        }
    }

    /**
     * Set the Android Context for accessing resources (e.g., string resources).
     */
    public void setContext(Context context) {
        this.context = context;
    }

    /** Load indicators from a folder containing .json or .stix2 files. */
    public void loadFromDirectory(File dir) {
        File[] files = (dir != null) ? dir.listFiles((d, name) -> name.endsWith(".json") || name.endsWith(".stix2")) : null;
        if (files == null) {
            throw new IllegalArgumentException("IOC directory is null or does not exist");
        }

        for (File f : files) {
            JSONObject root = safeReadJsonObject(f);
            if (root == null) continue;

            // Case 1: STIX 2.x bundle-like JSON: { "objects": [ { "type":"indicator", "pattern":"[...]"} ] }
            JSONArray objects = root.optJSONArray("objects");
            if (objects != null) {
                for (int i = 0; i < objects.length(); i++) {
                    JSONObject node = objects.optJSONObject(i);
                    if (node == null) continue;
                    if ("indicator".equals(node.optString("type", ""))) {
                        String pattern = node.optString("pattern", null);
                        addPattern(pattern);
                    }
                }
                continue;
            }

            // Case 2: MVT-style JSON: { "indicators": [ { "domain-name:value": ["a.com", ...], ... } ] }
            JSONArray arr = root.optJSONArray("indicators");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject coll = arr.optJSONObject(i);
                    if (coll == null) continue;

                    // Process all configured indicator types
                    for (Map.Entry<IndicatorType, Set<String>> entry : INDICATOR_CONFIG.entrySet()) {
                        IndicatorType type = entry.getKey();
                        Set<String> keys = entry.getValue();
                        Trie.TrieBuilder builder = trieBuilders.get(type);
                        if (builder != null) {
                            for (String key : keys) {
                                addField(builder, coll, key, null);
                            }
                        }
                    }
                }
            }
        }
        buildTries();
    }

    /** Build the Aho-Corasick tries from the builders. */
    private void buildTries() {
        for (Map.Entry<IndicatorType, Trie.TrieBuilder> entry : trieBuilders.entrySet()) {
            tries.put(entry.getKey(), entry.getValue().build());
        }
    }

    /** Parse a single STIX pattern like: "[domain-name:value = 'evil.com']" */
    private void addPattern(String pattern) {
        if (pattern == null) return;
        String p = pattern.trim();
        if (p.startsWith("[") && p.endsWith("]")) {
            p = p.substring(1, p.length() - 1);
        }
        String[] kv = p.split("=", 2);
        if (kv.length != 2) return;

        String key = kv[0].trim();
        String value = kv[1].trim();
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        String vLower = value.toLowerCase();

        // Find which indicator type this STIX pattern key belongs to
        for (Map.Entry<IndicatorType, Set<String>> entry : INDICATOR_CONFIG.entrySet()) {
            Set<String> keys = entry.getValue();
            if (keys.contains(key)) {
                Trie.TrieBuilder builder = trieBuilders.get(entry.getKey());
                if (builder != null) {
                    builder.addKeyword(vLower);
                } else {
                    Log.d(TAG, "Indicator type not supported: " + entry.getKey());
                }
                return;
            }
        }
    }

    /** Add values from indicators JSON (each key can be a single string or an array). */
    private void addField(Trie.TrieBuilder builder, JSONObject coll, String key, List<String> store) {
        if (coll == null) return;

        Object node = coll.opt(key);
        if (node == null) return;

        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                String s = toNonBlank(arr.opt(i));
                if (s != null) {
                    String lower = s.toLowerCase();
                    builder.addKeyword(lower);
                    if (store != null) store.add(lower);
                }
            }
        } else {
            String s = toNonBlank(node);
            if (s != null) {
                String lower = s.toLowerCase();
                builder.addKeyword(lower);
                if (store != null) store.add(lower);
            }
        }
    }

    private static String toNonBlank(Object o) {
        if (o == null) return null;
        String s = o.toString();
        if (s == null) return null;
        if (s.trim().isEmpty()) return null;
        return s;
    }

    /** Match string against loaded indicators. */
    public List<Detection> matchString(String s, IndicatorType type) {
        if (s == null || context == null) return Collections.emptyList();
        
        Trie trie = tries.get(type);
        if (trie == null) return Collections.emptyList();
        
        String lower = s.toLowerCase();
        List<Detection> detections = new ArrayList<>();

        for (Emit e : trie.parseText(lower)) {
            detections.add(new Detection(
                AlertLevel.CRITICAL,
                context.getString(R.string.mvt_ioc_title),
                String.format(context.getString(R.string.mvt_ioc_message), type.name(), e.getKeyword(), s)
            ));
        }
        
        return detections;
    }

    // --------- tiny JSON helper ----------

    /** Safely read a JSON object from a file using core org.json. */
    private static JSONObject safeReadJsonObject(File f) {
        try (BufferedInputStream bin = new BufferedInputStream(new FileInputStream(f))) {
            // Read file into a String
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = bin.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }

            // Parse JSON from string
            JSONTokener tok = new JSONTokener(sb.toString());
            Object any = tok.nextValue();
            if (any instanceof JSONObject) {
                return (JSONObject) any;
            }
            if (any instanceof JSONArray) {
                JSONObject wrapper = new JSONObject();
                wrapper.put("objects", (JSONArray) any); // treat array as STIX-like bundle
                return wrapper;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
