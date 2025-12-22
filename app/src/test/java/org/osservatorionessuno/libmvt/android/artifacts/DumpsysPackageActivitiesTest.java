package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysPackageActivitiesTest {

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
        DumpsysPackageActivities dpa = new DumpsysPackageActivities();
        String data = readResource("android_data/dumpsys_packages.txt");
        dpa.parse(data);
        assertEquals(4, dpa.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, String> first = (Map<String, String>) dpa.getResults().get(0);
        assertEquals("com.samsung.android.app.social", first.get("package_name"));
        assertEquals("com.samsung.android.app.social/.feed.FeedsActivity", first.get("activity"));
    }

    @Test
    public void testIocCheck() throws Exception {
        DumpsysPackageActivities dpa = new DumpsysPackageActivities();
        String data = readResource("android_data/dumpsys_packages.txt");
        dpa.parse(data);

        Indicators ind = new Indicators();
        ind.loadFromDirectory(
                Paths.get("src", "test", "resources", "iocs").toFile()
        );
        dpa.setIndicators(ind);
        dpa.checkIndicators();

        assertEquals(0, dpa.getDetected().size());
    }
}
