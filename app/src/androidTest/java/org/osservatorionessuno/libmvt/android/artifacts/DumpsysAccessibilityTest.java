package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.android.TestResourceLoader;
import org.osservatorionessuno.libmvt.common.Indicators;
import org.osservatorionessuno.libmvt.common.IndicatorType;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysAccessibilityTest {

    @Test
    public void testParsing() throws Exception {
        DumpsysAccessibility da = new DumpsysAccessibility();
        String data = TestResourceLoader.readText("android_data/dumpsys_accessibility.txt");
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
        String data = TestResourceLoader.readText("android_data/dumpsys_accessibility_v14_or_later.txt");
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
        String data = TestResourceLoader.readText("android_data/dumpsys_accessibility.txt");
        da.parse(data);

        Path temp = TestResourceLoader.extractDirectory("iocs").toPath();

        // write an extra IOC json file (no Files.writeString)
        Path extra = temp.resolve("extra.json");
        try (BufferedWriter bw = Files.newBufferedWriter(extra, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write("{\"indicators\":[{\"app:id\":[\"com.sec.android.app.camera\"]}]}");
        }

        Indicators indicators = new Indicators();
        indicators.loadFromDirectory(temp.toFile());
        da.setIndicators(indicators);
        da.checkIndicators();

        assertEquals(1, da.getDetected().size());
        //assertEquals(IndicatorType.APP_ID, da.getDetected().get(0).getType()); // getType(), not .type()
    }
}
