package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.android.TestResourceLoader;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysBatteryHistoryTest {

    @Test
    public void testParsing() throws Exception {
        DumpsysBatteryHistory bh = new DumpsysBatteryHistory();
        String data = TestResourceLoader.readText("android_data/dumpsys_battery.txt");
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
        String data = TestResourceLoader.readText("android_data/dumpsys_battery.txt");
        bh.parse(data);

        Indicators ind = new Indicators();
        ind.loadFromDirectory(TestResourceLoader.extractDirectory("iocs"));
        bh.setIndicators(ind);
        bh.checkIndicators();

        assertEquals(0, bh.getDetected().size());
    }
}
