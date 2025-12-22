package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ProcessesTest {

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
        Processes p = new Processes();
        String data = readResource("android_data/ps.txt");
        p.parse(data);
        assertEquals(17, p.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) p.getResults().get(0);
        assertEquals("init", first.get("proc_name"));
    }

    @Test
    public void testIocCheck() throws Exception {
        Processes p = new Processes();
        String data = readResource("android_data/ps.txt");
        p.parse(data);

        Indicators indicators = new Indicators();
        indicators.loadFromDirectory(
                Paths.get("src", "test", "resources", "iocs").toFile()
        );
        p.setIndicators(indicators);
        p.checkIndicators();

        assertTrue(p.getDetected().size() > 0);
    }

    @Test
    public void testTruncatedProcessMatch() throws Exception {
        Processes p = new Processes();
        String data = "USER PID PPID VSZ RSS WCHAN ADDR S NAME\n" +
                "root 50 2 0 0 0 0 S com.bad.actor.ma\n";
        p.parse(data);

        Indicators indicators = new Indicators();
        indicators.loadFromDirectory(
                Paths.get("src", "test", "resources", "iocs").toFile()
        );
        p.setIndicators(indicators);
        p.checkIndicators();

        assertFalse(p.getDetected().isEmpty());
    }
}
