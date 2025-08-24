package org.osservatorionessuno.libmvt.android.parsers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BackupParserTest {
    private byte[] readBytes(String name) throws IOException {
        Path path = Path.of("src","test","resources",name);
        return Files.readAllBytes(path);
    }

    @Test
    public void testParsingNoEncryption() throws Exception {
        byte[] data = readBytes("android_backup/backup.ab");
        byte[] tar = BackupParser.parseBackupFile(data, null);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha.digest(tar);
        assertEquals("ce1ac5009fea5187a9f546b51e1446ba450243ae91d31dc779233ec0937b5d18",
                bytesToHex(digest));
        List<Map<String,Object>> sms = BackupParser.parseTarForSms(tar);
        assertEquals(2, sms.size());
    }

    @Test
    public void testParsingEncryption() throws Exception {
        byte[] data = readBytes("android_backup/backup2.ab");
        byte[] tar = BackupParser.parseBackupFile(data, "123456");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha.digest(tar);
        assertEquals("f365ace1effbc4902c6aeba241ca61544f8a96ad456c1861808ea87b7dd03896",
                bytesToHex(digest));
        List<Map<String,Object>> sms = BackupParser.parseTarForSms(tar);
        assertEquals(1, sms.size());
    }

    @Test
    public void testParsingCompression() throws Exception {
        byte[] data = readBytes("android_backup/backup3.ab");
        byte[] tar = BackupParser.parseBackupFile(data, null);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha.digest(tar);
        assertEquals("33e73df2ede9798dcb3a85c06200ee41c8f52dd2f2e50ffafcceb0407bc13e3a",
                bytesToHex(digest));
        List<Map<String,Object>> sms = BackupParser.parseTarForSms(tar);
        assertEquals(1, sms.size());
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }
}
