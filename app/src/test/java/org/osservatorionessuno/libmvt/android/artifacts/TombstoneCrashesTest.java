package org.osservatorionessuno.libmvt.android.artifacts;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TombstoneCrashesTest {

    private byte[] readBytes(String name) throws Exception {
        Path path = Paths.get("src", "test", "resources", name);
        byte[] bytes = Files.readAllBytes(path);
        System.out.printf("Read binary file: %s (%d bytes)%n", path, bytes.length);
        return bytes;
    }

    private String readText(String name) throws Exception {
        Path path = Paths.get("src", "test", "resources", name);
        StringBuilder sb = new StringBuilder(8192);
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        System.out.printf("Read text file: %s (%d chars)%n", path, sb.length());
        return sb.toString();
    }

    @Test
    public void testParsing() throws Exception {
        TombstoneCrashes tc = new TombstoneCrashes();
        String data = readText("android_data/tombstone_process.txt");
        tc.parse(data);

        System.out.printf("Parsed text tombstone, results: %d%n", tc.getResults().size());
        for (Object r : tc.getResults()) {
            System.out.println("Parsed record (text): " + r);
        }

        assertEquals(1, tc.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, Object> rec = (Map<String, Object>) tc.getResults().get(0);
        assertEquals("mtk.ape.decoder", rec.get("process_name"));
        assertEquals(25541, rec.get("pid"));
        assertEquals(1046, rec.get("uid"));
        assertEquals("/vendor/bin/hw/android.hardware.media.c2@1.2-mediatek",
                ((List<?>) rec.get("command_line")).get(0));
        assertEquals("2023-04-12 12:32:40.518290", rec.get("timestamp"));
    }

    @Test
    public void testParseProtobuf() throws Exception {
        TombstoneCrashes tc = new TombstoneCrashes();
        byte[] data = readBytes("android_data/tombstone_process.pb");
        tc.parseProtobuf(data);

        System.out.printf("Parsed protobuf tombstone, results: %d%n", tc.getResults().size());
        for (Object r : tc.getResults()) {
            System.out.println("Parsed record (protobuf): " + r);
        }

        assertEquals(1, tc.getResults().size());

        @SuppressWarnings("unchecked")
        Map<String, Object> rec = (Map<String, Object>) tc.getResults().get(0);
        assertEquals("mtk.ape.decoder", rec.get("process_name"));
        assertEquals(25541, rec.get("pid"));
        assertEquals(1046, rec.get("uid"));
        assertEquals("/vendor/bin/hw/android.hardware.media.c2@1.2-mediatek",
                ((List<?>) rec.get("command_line")).get(0));
        assertEquals("2023-04-12 12:32:40.518290", rec.get("timestamp"));
    }

    @Test
    @Disabled("Not implemented yet")
    public void testParseKernel() throws Exception {
        // Skipped: kernel tombstone parsing is not yet implemented
    }
}
