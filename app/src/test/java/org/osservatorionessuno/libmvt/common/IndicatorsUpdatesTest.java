package org.osservatorionessuno.libmvt.common;

import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

public class IndicatorsUpdatesTest {

    @Test
    public void testUpdateLocal() throws Exception {
        Path temp = Files.createTempDirectory("mvt");

        Path stix = Paths.get("src", "test", "resources", "stix2", "cytrox.stix2");
        String index =
                "indicators:\n" +
                        "  - name: local\n" +
                        "    type: download\n" +
                        "    download_url: " + stix.toUri().toString() + "\n";

        Path indexFile = Files.createTempFile(temp, "index", ".yaml");
        try (BufferedWriter bw = Files.newBufferedWriter(indexFile, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write(index);
        }

        IndicatorsUpdates updates = new IndicatorsUpdates(temp, indexFile.toUri().toString());
        updates.update();

        Path indicatorsDir = temp.resolve("iocs");
        // Match IndicatorsUpdates' filename logic: replace only http(s) scheme and slash/backslash with '_'
        String fileName = stix.toUri().toString()
                .replaceFirst("^https?://", "")
                .replaceAll("[/\\\\]", "_");
        assertTrue(Files.exists(indicatorsDir.resolve(fileName)));

        Indicators indicators = new Indicators();
        indicators.loadFromDirectory(indicatorsDir.toFile());
        assertFalse(indicators.matchString("shortenurls.me", IndicatorType.DOMAIN).isEmpty());
    }
}
