package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysPlatformCompatTest {

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
        DumpsysPlatformCompat pc = new DumpsysPlatformCompat();
        String data = readResource("android_data/dumpsys_platform_compat.txt");
        pc.parse(data);
        assertEquals(2, pc.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, String> first = (Map<String, String>) pc.getResults().get(0);
        assertEquals("org.torproject.torbrowser", first.get("package_name"));
    }

    @Test
    public void testIocCheck() throws Exception {
        DumpsysPlatformCompat pc = new DumpsysPlatformCompat();
        String data = readResource("android_data/dumpsys_platform_compat.txt");
        pc.parse(data);

        Indicators ind = new Indicators();
        ind.loadFromDirectory(
                Paths.get("src", "test", "resources", "iocs").toFile()
        );
        pc.setIndicators(ind);
        pc.checkIndicators();

        assertEquals(0, pc.getDetected().size());
    }
}
