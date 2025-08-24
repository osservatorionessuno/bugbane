package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class AndroidArtifactTest {

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
    public void testExtractDumpsysSection() throws Exception {
        String dumpsys = readResource("androidqf/dumpsys.txt");
        String section = AndroidArtifact.extractDumpsysSection(dumpsys, "DUMP OF SERVICE package:");
        assertNotNull(section);
        assertEquals(3907, section.length());
    }
}
