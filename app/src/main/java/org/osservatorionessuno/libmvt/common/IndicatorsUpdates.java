package org.osservatorionessuno.libmvt.common;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IndicatorsUpdates {
    private static final String DEFAULT_GITHUB_RAW = "https://raw.githubusercontent.com/%s/%s/%s/%s";
    public static final Path MVT_DATA_FOLDER = Paths.get(System.getProperty("user.home"), ".mvt");

    private final Path latestUpdatePath;
    private final Path latestCheckPath;
    private final Path indicatorsFolder;

    private final String indexUrl;
    private final String githubRawUrl;

    public IndicatorsUpdates() {
        this(null, null);
    }

    public IndicatorsUpdates(Path dataFolder, String indexUrl) {
        Path base = (dataFolder == null) ? MVT_DATA_FOLDER : dataFolder;
        this.indexUrl = indexUrl;
        this.githubRawUrl = DEFAULT_GITHUB_RAW;
        this.latestUpdatePath = base.resolve("latest_indicators_update");
        this.latestCheckPath = base.resolve("latest_indicators_check");
        this.indicatorsFolder = base.resolve("indicators");

        try { Files.createDirectories(base); } catch (IOException ignored) {}
        try { Files.createDirectories(indicatorsFolder); } catch (IOException ignored) {}
    }

    /** Return the folder where indicators are stored. */
    public Path getIndicatorsFolder() {
        return indicatorsFolder;
    }

    // --------------------- Networking & IO helpers ---------------------

    private static String httpGetString(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            // allow some redirects that might already be followed
            InputStream err = conn.getErrorStream();
            if (err != null) err.close();
            conn.disconnect();
            return null;
        }
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             InputStreamReader isr = new InputStreamReader(in, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static boolean httpGetToFile(String url, Path dest, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            InputStream err = conn.getErrorStream();
            if (err != null) err.close();
            conn.disconnect();
            return false;
        }
        Files.createDirectories(dest.getParent());
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dest.toFile()))) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
        return true;
    }

    private static String readSmallFile(Path p) throws IOException {
        // Avoid Files.readString for Android; do a simple reader
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) sb.append(buf, 0, n);
            return sb.toString();
        }
    }

    private static void writeSmallFile(Path p, String s) throws IOException {
        Files.createDirectories(p.getParent());
        try (BufferedWriter bw = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            bw.write(s);
        }
    }

    // --------------------- Index loading ---------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> getRemoteIndex() throws IOException, InterruptedException {
        final String url = (indexUrl != null)
                ? indexUrl
                : String.format(githubRawUrl, "mvt-project", "mvt-indicators", "main", "indicators.yaml");

        String text;
        if (url.startsWith("file://")) {
            Path p = Paths.get(URI.create(url));
            text = readSmallFile(p);
        } else {
            text = httpGetString(url, 15000);
            if (text == null) return null;
        }

        // If the file is actually JSON, accept it directly
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            // extremely small JSON reader for the expected shape:
            // { "indicators": [ { "type": "...", "github": { ... } }, { "download_url": "..." } ] }
            return JsonMini.parseIndex(trimmed);
        }

        // Otherwise parse the known YAML shape (dependency-free)
        return YamlMini.parseIndex(text);
    }

    private String downloadRemoteIoc(String url) throws IOException, InterruptedException {
        Files.createDirectories(indicatorsFolder);
        String fileName = url.replaceFirst("^https?://", "").replaceAll("[/\\\\]", "_");
        Path dest = indicatorsFolder.resolve(fileName);

        if (url.startsWith("file://")) {
            Path p = Paths.get(URI.create(url));
            Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return dest.toString();
        }

        boolean ok = httpGetToFile(url, dest, 15000);
        return ok ? dest.toString() : null;
    }

    private long getLatestCheck() {
        try {
            return Long.parseLong(readSmallFile(latestCheckPath).trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private void setLatestCheck() {
        try { writeSmallFile(latestCheckPath, Long.toString(Instant.now().getEpochSecond())); } catch (IOException ignored) {}
    }

    private long getLatestUpdate() {
        try {
            return Long.parseLong(readSmallFile(latestUpdatePath).trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private void setLatestUpdate() {
        try { writeSmallFile(latestUpdatePath, Long.toString(Instant.now().getEpochSecond())); } catch (IOException ignored) {}
    }

    /** Download a single IOC file from a URL into the indicators folder. */
    public Path download(String url) throws IOException, InterruptedException {
        String dl = downloadRemoteIoc(url);
        return dl != null ? Paths.get(dl) : null;
    }

    public void update() throws IOException, InterruptedException {
        setLatestCheck();
        Map<String, Object> index = getRemoteIndex();
        if (index == null) return;

        Object indicators = index.get("indicators");
        if (!(indicators instanceof Iterable<?>)) return;
        Iterable<?> inds = (Iterable<?>) indicators;

        for (Object obj : inds) {
            if (!(obj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;

            String url;
            String type = stringOrEmpty(map.get("type"));
            if ("github".equals(type)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> gh = (Map<String, Object>) map.get("github");
                if (gh == null) continue;
                String owner  = stringOrEmpty(gh.get("owner"));
                String repo   = stringOrEmpty(gh.get("repo"));
                String branch = stringOrEmptyDefault(gh.get("branch"), "main");
                String path   = stringOrEmpty(gh.get("path"));
                if (owner.isEmpty() || repo.isEmpty() || path.isEmpty()) continue;
                url = String.format(githubRawUrl, owner, repo, branch, path);
            } else {
                url = stringOrEmpty(map.get("download_url"));
            }

            if (url.trim().isEmpty()) continue;
            downloadRemoteIoc(url);
        }
        setLatestUpdate();
    }

    private static String stringOrEmpty(Object o) {
        return (o == null) ? "" : o.toString();
    }
    private static String stringOrEmptyDefault(Object o, String dflt) {
        String s = stringOrEmpty(o);
        return s.isEmpty() ? dflt : s;
    }

    // --------------------- Minimal YAML/JSON readers ---------------------

    /**
     * Very small YAML reader for the expected indicators.yaml shape.
     * Not a general-purpose YAML parser.
     */
    static class YamlMini {
        static Map<String, Object> parseIndex(String text) {
            Map<String, Object> root = new HashMap<>();
            List<Map<String, Object>> indicators = new ArrayList<>();
            root.put("indicators", indicators);

            String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");

            boolean inIndicators = false;
            Map<String, Object> currentItem = null;
            boolean inGithub = false;
            Map<String, Object> currentGithub = null;
            int githubIndent = -1;
            int itemIndent = -1;

            for (String raw : lines) {
                String line = stripComment(raw);
                if (line.trim().isEmpty()) continue;

                int indent = leadingSpaces(line);
                String t = line.trim();

                if (!inIndicators) {
                    if (t.equals("indicators:")) {
                        inIndicators = true;
                    }
                    continue;
                }

                // new list item
                if (t.startsWith("- ")) {
                    currentItem = new HashMap<>();
                    indicators.add(currentItem);
                    inGithub = false;
                    currentGithub = null;
                    itemIndent = indent;
                    t = t.substring(2).trim();
                    if (!t.isEmpty()) {
                        // handle inline "key: value" after "- "
                        kvPut(currentItem, t);
                    }
                    continue;
                }

                if (currentItem == null) continue; // ignore anything before first item

                // leaving nested github block if indentation decreases
                if (inGithub && indent <= githubIndent) {
                    inGithub = false;
                    currentGithub = null;
                }

                // within current item (deeper indent than itemIndent)
                if (indent > itemIndent) {
                    if (t.equals("github:")) {
                        inGithub = true;
                        currentGithub = new HashMap<>();
                        currentItem.put("github", currentGithub);
                        githubIndent = indent;
                        continue;
                    }
                    if (inGithub) {
                        kvPut(currentGithub, t);
                    } else {
                        kvPut(currentItem, t);
                    }
                } else if (indent <= itemIndent) {
                    // a sibling or parent — we’ll wait for next "- " to start new item
                    // (no-op)
                }
            }

            return root;
        }

        private static void kvPut(Map<String, Object> target, String kvLine) {
            int idx = kvLine.indexOf(':');
            if (idx < 0) return;
            String k = kvLine.substring(0, idx).trim();
            String v = kvLine.substring(idx + 1).trim();
            // strip surrounding quotes if present
            if ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\""))) {
                v = v.substring(1, v.length() - 1);
            }
            target.put(k, v);
        }

        private static int leadingSpaces(String s) {
            int i = 0;
            while (i < s.length() && s.charAt(i) == ' ') i++;
            return i;
        }

        private static String stripComment(String s) {
            int i = s.indexOf('#');
            if (i >= 0) return s.substring(0, i);
            return s;
        }
    }

    /**
     * Minimal JSON reader for the very small expected index shape.
     * If your index is true JSON, this avoids adding a JSON dependency.
     * NOTE: This is deliberately tiny; if JSON grows, plug in a JSON lib.
     */
    static class JsonMini {
        static Map<String, Object> parseIndex(String json) {
            // Extremely small and permissive reader:
            // we will look for "indicators":[ ... ] and then for objects within it,
            // extracting "type", "download_url", and "github" { owner, repo, branch, path }.
            Map<String, Object> root = new HashMap<>();
            List<Map<String, Object>> indicators = new ArrayList<>();
            root.put("indicators", indicators);

            String s = json.replace("\r", "").trim();

            int indStart = s.indexOf("\"indicators\"");
            if (indStart < 0) return root;
            int arrStart = s.indexOf('[', indStart);
            if (arrStart < 0) return root;
            int arrEnd = findMatchingBracket(s, arrStart, '[', ']');
            if (arrEnd < 0) return root;

            String arr = s.substring(arrStart + 1, arrEnd);
            int idx = 0;
            while (idx < arr.length()) {
                int objStart = arr.indexOf('{', idx);
                if (objStart < 0) break;
                int objEnd = findMatchingBracket(arr, objStart, '{', '}');
                if (objEnd < 0) break;
                String obj = arr.substring(objStart + 1, objEnd);

                Map<String, Object> item = new HashMap<>();
                // crude key scanning
                String type = extractJsonString(obj, "type");
                if (type != null) item.put("type", type);
                String dl = extractJsonString(obj, "download_url");
                if (dl != null) item.put("download_url", dl);

                // nested github
                int ghStart = obj.indexOf("\"github\"");
                if (ghStart >= 0) {
                    int braceStart = obj.indexOf('{', ghStart);
                    int braceEnd = (braceStart >= 0) ? findMatchingBracket(obj, braceStart, '{', '}') : -1;
                    if (braceStart >= 0 && braceEnd > braceStart) {
                        String gh = obj.substring(braceStart + 1, braceEnd);
                        Map<String, Object> gm = new HashMap<>();
                        putIfNotNull(gm, "owner", extractJsonString(gh, "owner"));
                        putIfNotNull(gm, "repo", extractJsonString(gh, "repo"));
                        putIfNotNull(gm, "branch", extractJsonString(gh, "branch"));
                        putIfNotNull(gm, "path", extractJsonString(gh, "path"));
                        item.put("github", gm);
                    }
                }

                indicators.add(item);
                idx = objEnd + 1;
            }

            return root;
        }

        private static void putIfNotNull(Map<String, Object> m, String k, String v) {
            if (v != null) m.put(k, v);
        }

        private static int findMatchingBracket(String s, int openPos, char open, char close) {
            int depth = 0;
            for (int i = openPos; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == open) depth++;
                else if (c == close) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }

        private static String extractJsonString(String obj, String key) {
            String needle = "\"" + key + "\"";
            int k = obj.indexOf(needle);
            if (k < 0) return null;
            int colon = obj.indexOf(':', k + needle.length());
            if (colon < 0) return null;
            int q1 = obj.indexOf('"', colon + 1);
            if (q1 < 0) return null;
            int q2 = findStringEnd(obj, q1 + 1);
            if (q2 < 0) return null;
            String raw = obj.substring(q1 + 1, q2);
            return unescapeJsonString(raw);
        }

        private static int findStringEnd(String s, int start) {
            boolean esc = false;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (esc) { esc = false; continue; }
                if (c == '\\') { esc = true; continue; }
                if (c == '"') return i;
            }
            return -1;
        }

        private static String unescapeJsonString(String raw) {
            // minimal unescape for common sequences
            StringBuilder sb = new StringBuilder(raw.length());
            boolean esc = false;
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (!esc) {
                    if (c == '\\') { esc = true; continue; }
                    sb.append(c);
                } else {
                    switch (c) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 < raw.length()) {
                                String hex = raw.substring(i + 1, i + 5);
                                try { sb.append((char) Integer.parseInt(hex, 16)); }
                                catch (Exception ignored) {}
                                i += 4;
                            }
                            break;
                        default: sb.append(c); break;
                    }
                    esc = false;
                }
            }
            return sb.toString();
        }
    }
}
