package org.osservatorionessuno.libmvt.android.artifacts;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Indicators;
import org.osservatorionessuno.libmvt.common.IndicatorType;
import org.osservatorionessuno.libmvt.android.TestResourceLoader;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysPackagesTest {

    @Test
    public void testParsing() throws Exception {
        DumpsysPackages dpa = new DumpsysPackages();
        String data = TestResourceLoader.readText("android_data/dumpsys_packages.txt");
        dpa.parse(data);
        assertEquals(2, dpa.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) dpa.getResults().get(0);
        assertEquals("com.samsung.android.provider.filterprovider", first.get("package_name"));
        assertEquals("5.0.07", first.get("version_name"));
    }

    @Test
    public void testIocCheck() throws Exception {
        DumpsysPackages dpa = new DumpsysPackages();
        String data = TestResourceLoader.readText("android_data/dumpsys_packages.txt");
        dpa.parse(data);

        Indicators indicators = new Indicators();
        indicators.loadFromDirectory(TestResourceLoader.extractDirectory("iocs"));
        dpa.setIndicators(indicators);
        dpa.checkIndicators();

        assertTrue(dpa.getDetected().size() > 0);
    }

    @Test
    public void testRootPackageDetection() {
        DumpsysPackages dpa = new DumpsysPackages();
        dpa.setContext(ApplicationProvider.getApplicationContext());
        
        String sample = "Packages:\n  Package [com.topjohnwu.magisk] (test)\n    userId=0\n";
        dpa.parse(sample);
        dpa.checkIndicators();
        assertFalse(dpa.getDetected().isEmpty());
    }
}
