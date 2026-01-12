package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.android.TestResourceLoader;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysPackageActivitiesTest {

    @Test
    public void testParsing() throws Exception {
        DumpsysPackageActivities dpa = new DumpsysPackageActivities();
        String data = TestResourceLoader.readText("android_data/dumpsys_packages.txt");
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
        String data = TestResourceLoader.readText("android_data/dumpsys_packages.txt");
        dpa.parse(data);

        Indicators ind = new Indicators();
        ind.loadFromDirectory(TestResourceLoader.extractDirectory("iocs"));
        dpa.setIndicators(ind);
        dpa.checkIndicators();

        assertEquals(0, dpa.getDetected().size());
    }
}
