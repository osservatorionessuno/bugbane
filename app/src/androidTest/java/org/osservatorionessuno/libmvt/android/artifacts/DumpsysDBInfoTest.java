package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.android.TestResourceLoader;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysDBInfoTest {

    @Test
    public void testParsing() throws Exception {
        DumpsysDBInfo dbi = new DumpsysDBInfo();
        String data = TestResourceLoader.readText("android_data/dumpsys_dbinfo.txt");
        dbi.parse(data);

        assertEquals(5, dbi.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, String> first = (Map<String, String>) dbi.getResults().get(0);
        assertEquals("executeForCursorWindow", first.get("action"));
        assertEquals("PRAGMA database_list;", first.get("sql"));
        assertEquals("/data/user/0/com.wssyncmldm/databases/idmsdk.db", first.get("path"));
    }

    @Test
    public void testIocCheck() throws Exception {
        DumpsysDBInfo dbi = new DumpsysDBInfo();
        String data = TestResourceLoader.readText("android_data/dumpsys_dbinfo.txt");
        dbi.parse(data);

        Indicators ind = new Indicators();
        ind.loadFromDirectory(TestResourceLoader.extractDirectory("iocs"));
        dbi.setIndicators(ind);
        dbi.checkIndicators();

        assertEquals(0, dbi.getDetected().size());
    }
}
