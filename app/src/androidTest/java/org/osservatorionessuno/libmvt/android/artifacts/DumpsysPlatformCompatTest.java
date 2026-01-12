package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.android.TestResourceLoader;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysPlatformCompatTest {

    @Test
    public void testParsing() throws Exception {
        DumpsysPlatformCompat pc = new DumpsysPlatformCompat();
        String data = TestResourceLoader.readText("android_data/dumpsys_platform_compat.txt");
        pc.parse(data);
        assertEquals(2, pc.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, String> first = (Map<String, String>) pc.getResults().get(0);
        assertEquals("org.torproject.torbrowser", first.get("package_name"));
    }

    @Test
    public void testIocCheck() throws Exception {
        DumpsysPlatformCompat pc = new DumpsysPlatformCompat();
        String data = TestResourceLoader.readText("android_data/dumpsys_platform_compat.txt");
        pc.parse(data);

        Indicators ind = new Indicators();
        ind.loadFromDirectory(TestResourceLoader.extractDirectory("iocs"));
        pc.setIndicators(ind);
        pc.checkIndicators();

        assertEquals(0, pc.getDetected().size());
    }
}
