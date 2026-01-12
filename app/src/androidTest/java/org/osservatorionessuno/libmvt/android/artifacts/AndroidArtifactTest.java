package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Test;
import org.osservatorionessuno.libmvt.android.TestResourceLoader;

import static org.junit.jupiter.api.Assertions.*;

public class AndroidArtifactTest {

    @Test
    public void testExtractDumpsysSection() throws Exception {
        String dumpsys = TestResourceLoader.readText("androidqf/dumpsys.txt");
        String section = AndroidArtifact.extractDumpsysSection(dumpsys, "DUMP OF SERVICE package:");
        assertNotNull(section);
        assertEquals(3907, section.length());
    }
}
