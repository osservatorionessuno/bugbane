package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Indicators;
import org.osservatorionessuno.libmvt.common.IndicatorType;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysPackagesTest {

    private String readResource(String name) throws Exception {
        Path path = Paths.get("src", "test", "resources", name);
        StringBuilder sb = new StringBuilder(8192);
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    @Test
    public void testParsing() throws Exception {
        DumpsysPackages dpa = new DumpsysPackages();
        String data = readResource("android_data/dumpsys_packages.txt");
        dpa.parse(data);
        assertEquals(2, dpa.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) dpa.getResults().get(0);
        assertEquals("com.samsung.android.provider.filterprovider", first.get("package_name"));
        assertEquals("5.0.07", first.get("version_name"));
    }

    @Test
    public void testIocCheck() throws Exception {
        DumpsysPackages dpa = new DumpsysPackages();
        String data = readResource("android_data/dumpsys_packages.txt");
        dpa.parse(data);

        Indicators indicators = Indicators.loadFromDirectory(
                Paths.get("src", "test", "resources", "iocs").toFile()
        );
        dpa.setIndicators(indicators);
        dpa.checkIndicators();

        assertTrue(dpa.getDetected().size() > 0);
    }

    @Test
    public void testRootPackageDetection() {
        DumpsysPackages dpa = new DumpsysPackages();
        String sample = "Packages:\n  Package [com.topjohnwu.magisk] (test)\n    userId=0\n";
        dpa.parse(sample);
        dpa.checkIndicators();
        assertFalse(dpa.getDetected().isEmpty());
    }
}
