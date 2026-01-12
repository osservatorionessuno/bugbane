package org.osservatorionessuno.libmvt.android;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Artifact;
import org.osservatorionessuno.libmvt.common.Indicators;
import org.osservatorionessuno.libmvt.android.TestResourceLoader;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ForensicRunnerTest {

    @Test
    public void testRunAllModules() throws Exception {
        File dir = TestResourceLoader.extractDirectory("androidqf");
        File iocDir = TestResourceLoader.extractDirectory("iocs");
        Indicators ind = new Indicators();
        ind.loadFromDirectory(iocDir);

        Context context = ApplicationProvider.getApplicationContext();
        ForensicRunner runner = new ForensicRunner(dir, context);
        runner.setIndicators(ind);

        Map<String, Artifact> res = runner.runAll();

        assertTrue(res.containsKey("processes"));
        Artifact proc = res.get("processes");
        assertNotNull(proc);
        assertEquals(15, proc.getResults().size());

        assertTrue(res.containsKey("aqf_getprop"));
        assertNotNull(res.get("aqf_getprop"));
        assertEquals(10, res.get("aqf_getprop").getResults().size());
    }

    @Test
    public void testRunSingleModule() throws Exception {
        File dir = TestResourceLoader.extractDirectory("androidqf");

        Context context = ApplicationProvider.getApplicationContext();
        ForensicRunner runner = new ForensicRunner(dir, context);
        Artifact art = runner.runModule("aqf_getprop");

        assertNotNull(art);
        assertEquals(10, art.getResults().size());
    }
}
