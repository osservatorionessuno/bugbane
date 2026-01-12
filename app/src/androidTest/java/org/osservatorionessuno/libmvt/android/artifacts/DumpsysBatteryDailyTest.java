package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.android.TestResourceLoader;
import org.osservatorionessuno.libmvt.common.Indicators;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysBatteryDailyTest {

    @Test
    public void testParsing() throws Exception {
        DumpsysBatteryDaily bd = new DumpsysBatteryDaily();
        String data = TestResourceLoader.readText("android_data/dumpsys_battery.txt");
        bd.parse(data);
        assertEquals(3, bd.getResults().size());
    }

    @Test
    public void testIocCheck() throws Exception {
        DumpsysBatteryDaily bd = new DumpsysBatteryDaily();
        String data = TestResourceLoader.readText("android_data/dumpsys_battery.txt");
        bd.parse(data);

        Indicators ind = new Indicators();
        ind.loadFromDirectory(TestResourceLoader.extractDirectory("iocs"));
        bd.setIndicators(ind);
        bd.checkIndicators();

        assertEquals(1, bd.getDetected().size());
    }
}
