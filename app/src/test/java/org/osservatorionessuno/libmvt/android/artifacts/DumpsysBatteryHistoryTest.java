package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysBatteryHistoryTest {

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
        DumpsysBatteryHistory bh = new DumpsysBatteryHistory();
        String data = readResource("android_data/dumpsys_battery.txt");
        bh.parse(data);
        assertEquals(5, bh.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, String> first = (Map<String, String>) bh.getResults().get(0);
        assertEquals("com.samsung.android.app.reminder", first.get("package_name"));

        @SuppressWarnings("unchecked")
        Map<String, String> second = (Map<String, String>) bh.getResults().get(1);
        assertEquals("end_job", second.get("event"));

        @SuppressWarnings("unchecked")
        Map<String, String> third = (Map<String, String>) bh.getResults().get(2);
        assertEquals("start_top", third.get("event"));
        assertEquals("u0a280", third.get("uid"));
        assertEquals("com.whatsapp", third.get("package_name"));

        @SuppressWarnings("unchecked")
        Map<String, String> fourth = (Map<String, String>) bh.getResults().get(3);
        assertEquals("end_top", fourth.get("event"));

        @SuppressWarnings("unchecked")
        Map<String, String> fifth = (Map<String, String>) bh.getResults().get(4);
        assertEquals("com.sec.android.app.launcher", fifth.get("package_name"));
    }

    @Test
    public void testIocCheck() throws Exception {
        DumpsysBatteryHistory bh = new DumpsysBatteryHistory();
        String data = readResource("android_data/dumpsys_battery.txt");
        bh.parse(data);

        Indicators ind = Indicators.loadFromDirectory(
                Paths.get("src", "test", "resources", "iocs").toFile()
        );
        bh.setIndicators(ind);
        bh.checkIndicators();

        assertEquals(0, bh.getDetected().size());
    }
}
