package org.osservatorionessuno.libmvt.android.artifacts;


import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;

/** Parser for dumpsys adb output. */
public class DumpsysAdb extends AndroidArtifact {
    private static final Set<String> MULTILINE = Set.of("user_keys", "keystore");

    @Override
    public void parse(String input) throws Exception {
        results.clear();
        if (input == null || input.contains("Can't find service: adb")) return;
        Map<String, Object> res = new HashMap<>();
        String[] lines = input.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("user_keys=")) {
                String val = line.substring(10).trim();
                List<Map<String, String>> info = new ArrayList<>();
                info.add(calculateKeyInfo(val));
                res.put("user_keys", info);
            }
            if (line.startsWith("keystore=")) {
                String after = line.substring(9).trim();
                if (after.startsWith("<?xml")) {
                    StringBuilder xml = new StringBuilder(after).append('\n');
                    for (int j = i + 1; j < lines.length; j++) {
                        xml.append(lines[j]).append('\n');
                        if (lines[j].contains("</keyStore>")) { i = j; break; }
                    }
                    res.put("keystore", parseXml(xml.toString()));
                } else {
                    res.put("keystore", List.of(after));
                }
            }
        }
        results.add(res);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> indentedDumpParser(String data) {
        Map<String, Object> root = new HashMap<>();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(root);
        int curIndent = 0;
        boolean inMultiline = false;
        for (String rawLine : data.split("\n")) {
            String line = rawLine.replaceAll("\r", "");
            int indent = line.length() - line.stripLeading().length();
            if (indent < curIndent) {
                while (curIndent > indent && stack.size() > 1) {
                    stack.pop();
                    curIndent -= 2;
                }
                curIndent = indent;
            } else {
                curIndent = indent;
            }
            String[] parts = line.stripLeading().split("=", 2);
            String key = parts[0];
            Object current = stack.peek();
            if (inMultiline) {
                if (key.isEmpty()) {
                    inMultiline = false;
                    stack.pop();
                } else {
                    ((List<String>) current).add(line.strip());
                }
                continue;
            }
            if (key.equals("}")) { stack.pop(); continue; }
            String value = parts.length > 1 ? parts[1] : "";
            if ("{".equals(value)) {
                Map<String, Object> map = new HashMap<>();
                ((Map<String, Object>) current).put(key, map);
                stack.push(map);
            } else if (MULTILINE.contains(key)) {
                List<String> list = new ArrayList<>();
                list.add(value);
                ((Map<String, Object>) current).put(key, list);
                stack.push(list);
                inMultiline = true;
            } else {
                ((Map<String, Object>) current).put(key, value);
            }
        }
        return root;
    }

    private List<Map<String, String>> parseXml(String xml) throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes()));
        List<Map<String, String>> list = new ArrayList<>();
        var nodes = doc.getElementsByTagName("adbKey");
        for (int i = 0; i < nodes.getLength(); i++) {
            var el = nodes.item(i);
            String key = el.getAttributes().getNamedItem("key").getNodeValue();
            Map<String, String> map = calculateKeyInfo(key);
            var lc = el.getAttributes().getNamedItem("lastConnection");
            if (lc != null) map.put("last_connected", lc.getNodeValue());
            list.add(map);
        }
        return list;
    }

    private Map<String, String> calculateKeyInfo(String userKey) throws Exception {
        String keyBase64;
        String user = "";
        int space = userKey.indexOf(' ');
        if (space >= 0) {
            keyBase64 = userKey.substring(0, space);
            user = userKey.substring(space + 1);
        } else {
            keyBase64 = userKey;
        }
        String fingerprint = "";
        try {
            byte[] raw = Base64.getDecoder().decode(keyBase64);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02X", b));
            StringBuilder col = new StringBuilder();
            for (int i = 0; i < hex.length(); i += 2) {
                if (i > 0) col.append(':');
                col.append(hex.substring(i, i + 2));
            }
            fingerprint = col.toString();
        } catch (IllegalArgumentException e) {
            fingerprint = "";
        }
        Map<String, String> map = new HashMap<>();
        map.put("user", user);
        map.put("fingerprint", fingerprint);
        map.put("key", keyBase64);
        return map;
    }

    @Override
    public void checkIndicators() {
        // No IOC matching defined for this artifact
    }
}
