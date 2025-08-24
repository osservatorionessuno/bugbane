package org.osservatorionessuno.libmvt.android;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Artifact;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AndroidQFRunnerTest {

    @Test
    public void testRunAllModules() throws Exception {
        File dir = Paths.get("src", "test", "resources", "androidqf").toFile();
        File iocDir = Paths.get("src", "test", "resources", "iocs").toFile();

        Indicators ind = Indicators.loadFromDirectory(iocDir);

        AndroidQFRunner runner = new AndroidQFRunner(dir);
        runner.setIndicators(ind);

        Map<String, Artifact> res = runner.runAll();

        assertTrue(res.containsKey("processes"));
        Artifact proc = res.get("processes");
        assertNotNull(proc);
        assertEquals(15, proc.getResults().size());

        assertTrue(res.containsKey("getprop"));
        assertNotNull(res.get("getprop"));
        assertEquals(10, res.get("getprop").getResults().size());
    }

    @Test
    public void testRunSingleModule() throws Exception {
        File dir = Paths.get("src", "test", "resources", "androidqf").toFile();

        AndroidQFRunner runner = new AndroidQFRunner(dir);
        Artifact art = runner.runModule("getprop");

        assertNotNull(art);
        assertEquals(10, art.getResults().size());
    }
}
