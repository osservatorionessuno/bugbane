package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.common.Indicators;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DumpsysDBInfoTest {

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
        DumpsysDBInfo dbi = new DumpsysDBInfo();
        String data = readResource("android_data/dumpsys_dbinfo.txt");
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
        String data = readResource("android_data/dumpsys_dbinfo.txt");
        dbi.parse(data);

        Indicators ind = new Indicators();
        ind.loadFromDirectory(
                Paths.get("src", "test", "resources", "iocs").toFile()
        );
        dbi.setIndicators(ind);
        dbi.checkIndicators();

        assertEquals(0, dbi.getDetected().size());
    }
}
