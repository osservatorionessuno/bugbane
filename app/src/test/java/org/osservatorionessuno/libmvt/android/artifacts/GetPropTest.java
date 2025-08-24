package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class GetPropTest {

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
        GetProp gp = new GetProp();
        String data = readResource("android_data/getprop.txt");
        gp.parse(data);
        assertEquals(13, gp.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, String> first = (Map<String, String>) gp.getResults().get(0);
        assertEquals("af.fast_track_multiplier", first.get("name"));
        assertEquals("1", first.get("value"));
    }

    @Test
    public void testIocCheck() throws Exception {
        GetProp gp = new GetProp();
        String data = readResource("android_data/getprop.txt");
        gp.parse(data);

        Indicators indicators = Indicators.loadFromDirectory(
                Paths.get("src", "test", "resources", "iocs").toFile()
        );
        gp.setIndicators(indicators);
        gp.checkIndicators();

        assertTrue(gp.getDetected().size() > 0);
    }
}
