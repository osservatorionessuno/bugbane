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
import java.util.List;

/**
 * Loads indicators from .json and .stix2 (JSON) files and matches strings.
 * JSON parsing is done with Android's built-in org.json (no extra deps).
 * Keyword matching for DOMAIN/URL uses Aho-Corasick tries.
 */
public class Indicators {
    private final Trie domainTrie;
    private final Trie urlTrie;
    private final Trie processTrie;   // kept for symmetry (not used in matching; list used instead)
    private final Trie appIdTrie;     // kept for symmetry (not used in matching; list used instead)
    private final Trie propertyTrie;  // kept for symmetry (not used in matching; list used instead)
    private Context context;

    private Indicators(Trie domainTrie, Trie urlTrie, Trie processTrie, Trie appIdTrie, Trie propertyTrie) {
        this.domainTrie   = domainTrie;
        this.urlTrie      = urlTrie;
        this.processTrie  = processTrie;
        this.appIdTrie    = appIdTrie;
        this.propertyTrie = propertyTrie;
    }

    /**
     * Set the Android Context for accessing resources (e.g., string resources).
     */
    public void setContext(Context context) {
        this.context = context;
    }

    /** Load indicators from a folder containing .json or .stix2 files. */
    public static Indicators loadFromDirectory(File dir) {
        Trie.TrieBuilder domains    = Trie.builder().ignoreCase();
        Trie.TrieBuilder urls       = Trie.builder().ignoreCase();
        Trie.TrieBuilder processes  = Trie.builder().ignoreCase();
        Trie.TrieBuilder appIds     = Trie.builder().ignoreCase();
        Trie.TrieBuilder properties = Trie.builder().ignoreCase();

        File[] files = (dir != null) ? dir.listFiles((d, name) -> name.endsWith(".json") || name.endsWith(".stix2")) : null;
        if (files == null) {
            return new Indicators(domains.build(), urls.build(), processes.build(), appIds.build(), properties.build());
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
                        addPattern(domains, urls, processes, appIds, properties, pattern);
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

                    addField(domains,    coll, "domain-name:value",     null);
                    addField(domains,    coll, "ipv4-addr:value",       null);
                    addField(urls,       coll, "url:value",             null);
                    addField(processes,  coll, "process:name",          null);
                    addField(appIds,     coll, "app:id",                null);
                    addField(properties, coll, "android-property:name", null);
                }
            }
        }

        return new Indicators(domains.build(), urls.build(), processes.build(), appIds.build(), properties.build());
    }

    /** Parse a single STIX pattern like: "[domain-name:value = 'evil.com']" */
    private static void addPattern(Trie.TrieBuilder domains, Trie.TrieBuilder urls,
                                   Trie.TrieBuilder processes, Trie.TrieBuilder appIds,
                                   Trie.TrieBuilder properties,
                                   String pattern) {
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

        switch (key) {
            case "domain-name:value":
                domains.addKeyword(vLower);
                break;
            case "ipv4-addr:value":
                domains.addKeyword(vLower);
                break;
            case "url:value":
                urls.addKeyword(vLower);
                break;
            case "process:name":
                processes.addKeyword(vLower);
                break;
            case "app:id":
                appIds.addKeyword(vLower);
                break;
            case "android-property:name":
                properties.addKeyword(vLower);
                break;
            default:
                break;
        }
    }

    /** Add values from indicators JSON (each key can be a single string or an array). */
    private static void addField(Trie.TrieBuilder builder, JSONObject coll, String key, List<String> store) {
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
        if (s == null) return Collections.emptyList();
        String lower = s.toLowerCase();
        List<Detection> detections = new ArrayList<>();

        switch (type) {
            case DOMAIN: {
                for (Emit e : domainTrie.parseText(lower)) {
                    detections.add(new Detection(AlertLevel.CRITICAL, 
                        context.getString(R.string.mvt_ioc_title),
                        String.format(context.getString(R.string.mvt_ioc_message), type.name(), e.getKeyword(), s)));
                }
                break;
            }
            case URL: {
                for (Emit e : urlTrie.parseText(lower)) {
                    detections.add(new Detection(AlertLevel.CRITICAL, 
                        context.getString(R.string.mvt_ioc_title),
                        String.format(context.getString(R.string.mvt_ioc_message), type.name(), e.getKeyword(), s)));
                }
                break;
            }
            case PROCESS: {
                for (Emit e : processTrie.parseText(lower)) {
                    detections.add(new Detection(AlertLevel.CRITICAL, 
                        context.getString(R.string.mvt_ioc_title),
                        String.format(context.getString(R.string.mvt_ioc_message), type.name(), e.getKeyword(), s)));
                }
                break;
            }
            case APP_ID: {
                for (Emit e : appIdTrie.parseText(lower)) {
                    detections.add(new Detection(AlertLevel.CRITICAL, 
                        context.getString(R.string.mvt_ioc_title),
                        String.format(context.getString(R.string.mvt_ioc_message), type.name(), e.getKeyword(), s)));
                }
                break;
            }
            case PROPERTY: {
                for (Emit e : propertyTrie.parseText(lower)) {
                    detections.add(new Detection(AlertLevel.CRITICAL, 
                        context.getString(R.string.mvt_ioc_title),
                        String.format(context.getString(R.string.mvt_ioc_message), type.name(), e.getKeyword(), s)));
                }
                break;
            }
            default:
                break;
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
