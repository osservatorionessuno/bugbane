package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

public class DumpsysBatteryDailyTest {

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
        DumpsysBatteryDaily bd = new DumpsysBatteryDaily();
        String data = readResource("android_data/dumpsys_battery.txt");
        bd.parse(data);
        assertEquals(3, bd.getResults().size());
    }

    @Test
    public void testIocCheck() throws Exception {
        DumpsysBatteryDaily bd = new DumpsysBatteryDaily();
        String data = readResource("android_data/dumpsys_battery.txt");
        bd.parse(data);

        Indicators ind = Indicators.loadFromDirectory(
                Paths.get("src", "test", "resources", "iocs").toFile()
        );
        bd.setIndicators(ind);
        bd.checkIndicators();

        assertEquals(1, bd.getDetected().size());
    }
}
