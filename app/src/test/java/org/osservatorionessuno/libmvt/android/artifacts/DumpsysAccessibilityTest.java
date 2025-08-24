package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Indicators;
import org.osservatorionessuno.libmvt.common.IndicatorType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysAccessibilityTest {

    private String readResource(String name) throws IOException {
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
        DumpsysAccessibility da = new DumpsysAccessibility();
        String data = readResource("android_data/dumpsys_accessibility.txt");
        da.parse(data);
        assertEquals(4, da.getResults().size());
        @SuppressWarnings("unchecked")
        Map<String, String> first = (Map<String, String>) da.getResults().get(0);
        assertEquals("com.android.settings", first.get("package_name"));
        assertEquals(
                "com.android.settings/com.samsung.android.settings.development.gpuwatch.GPUWatchInterceptor",
                first.get("service")
        );
    }

    @Test
    public void testParsingV14Format() throws Exception {
        DumpsysAccessibility da = new DumpsysAccessibility();
        String data = readResource("android_data/dumpsys_accessibility_v14_or_later.txt");
        da.parse(data);
        assertEquals(1, da.getResults().size());
        @SuppressWarnings("unchecked")
        Map<String, String> first = (Map<String, String>) da.getResults().get(0);
        assertEquals("com.malware.accessibility", first.get("package_name"));
        assertEquals("com.malware.service.malwareservice", first.get("service"));
    }

    @Test
    public void testIocCheck() throws Exception {
        DumpsysAccessibility da = new DumpsysAccessibility();
        String data = readResource("android_data/dumpsys_accessibility.txt");
        da.parse(data);

        Path temp = Files.createTempDirectory("iocs");

        // copy bundled IOC files
        try (Stream<Path> stream = Files.list(Paths.get("src", "test", "resources", "iocs"))) {
            stream.forEach(p -> {
                try {
                    Files.copy(p, temp.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {}
            });
        }

        // write an extra IOC json file (no Files.writeString)
        Path extra = temp.resolve("extra.json");
        try (BufferedWriter bw = Files.newBufferedWriter(extra, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write("{\"indicators\":[{\"app:id\":[\"com.sec.android.app.camera\"]}]}");
        }

        Indicators indicators = Indicators.loadFromDirectory(temp.toFile());
        da.setIndicators(indicators);
        da.checkIndicators();

        assertEquals(1, da.getDetected().size());
        assertEquals(IndicatorType.APP_ID, da.getDetected().get(0).getType()); // getType(), not .type()
    }
}
