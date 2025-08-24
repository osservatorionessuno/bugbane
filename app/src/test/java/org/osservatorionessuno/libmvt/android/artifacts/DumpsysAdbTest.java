package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysAdbTest {
    private String readResource(String name) throws Exception {
        Path path = Paths.get("src", "test", "resources", name);
        StringBuilder sb = new StringBuilder(8192);
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    @Test
    public void testParsing() throws Exception {
        DumpsysAdb da = new DumpsysAdb();
        String data = readResource("android_data/dumpsys_adb.txt");
        da.parse(data);
        assertEquals(1, da.getResults().size());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) da.getResults().get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> keys = (List<Map<String, String>>) result.get("user_keys");
        assertEquals(1, keys.size());
        Map<String, String> first = keys.get(0);
        assertEquals("F0:A1:3D:8C:B3:F4:7B:09:9F:EE:8B:D8:38:2E:BD:C6", first.get("fingerprint"));
        assertEquals("user@linux", first.get("user"));
    }

    @Test
    public void testParsingXml() throws Exception {
        DumpsysAdb da = new DumpsysAdb();
        String data = readResource("android_data/dumpsys_adb_xml.txt");
        da.parse(data);
        assertEquals(1, da.getResults().size());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) da.getResults().get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> keys = (List<Map<String, String>>) result.get("user_keys");
        assertEquals(1, keys.size());
        Map<String, String> first = keys.get(0);
        assertEquals("F0:0B:27:08:E3:68:7B:FA:4C:79:A2:B4:BF:0E:CF:70", first.get("fingerprint"));
        assertEquals("user@laptop", first.get("user"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> ks = (List<Map<String, String>>) result.get("keystore");
        assertEquals("user@laptop", ks.get(0).get("user"));
        assertEquals("F0:0B:27:08:E3:68:7B:FA:4C:79:A2:B4:BF:0E:CF:70", ks.get(0).get("fingerprint"));
        assertEquals("1628501829898", ks.get(0).get("last_connected"));
    }
}
