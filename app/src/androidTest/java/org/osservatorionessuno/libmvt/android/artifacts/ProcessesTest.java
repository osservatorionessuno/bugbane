package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.android.TestResourceLoader;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import androidx.test.core.app.ApplicationProvider;

public class ProcessesTest {

    @Test
    public void testParsing() throws Exception {
        Processes p = new Processes();
        String data = TestResourceLoader.readText("android_data/ps.txt");
        p.parse(data);
        assertEquals(17, p.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) p.getResults().get(0);
        assertEquals("init", first.get("proc_name"));
    }

    @Test
    public void testIocCheck() throws Exception {
        Processes p = new Processes();
        String data = TestResourceLoader.readText("android_data/ps.txt");
        p.parse(data);

        Indicators indicators = new Indicators();
        indicators.loadFromDirectory(TestResourceLoader.extractDirectory("iocs"));
        p.setIndicators(indicators);
        p.checkIndicators();

        assertTrue(p.getDetected().size() > 0);
    }

    @Test
    public void testTruncatedProcessMatch() throws Exception {
        Processes p = new Processes();
        p.setContext(ApplicationProvider.getApplicationContext());

        String data = "USER PID PPID VSZ RSS WCHAN ADDR S NAME\n" +
                "root 50 2 0 0 0 0 S com.bad.actor.ma\n";
        p.parse(data);

        Indicators indicators = new Indicators();
        indicators.loadFromDirectory(TestResourceLoader.extractDirectory("iocs"));
        p.setIndicators(indicators);
        p.checkIndicators();

        assertFalse(p.getDetected().isEmpty());
    }
}
