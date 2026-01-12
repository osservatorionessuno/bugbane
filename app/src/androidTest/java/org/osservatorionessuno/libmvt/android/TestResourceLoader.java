package org.osservatorionessuno.libmvt.android;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utility helpers to read and copy instrumentation-only test assets.
 * <p>
 * Assets live under {@code src/androidTest/resources} and are not packaged
 * with the release app. They are bundled only into the androidTest APK and
 * accessed via the instrumentation context.
 */
public final class TestResourceLoader {

    private TestResourceLoader() {
    }

    /** Read a text asset using UTF-8. */
    public static String readText(String assetPath) throws IOException {
        AssetManager am = getAssetManager();
        try (InputStream is = am.open(assetPath);
             InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {
            StringBuilder sb = new StringBuilder(8192);
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    /** Copy a single asset file into the target app cache and return it. */
    public static File extractFile(String assetPath) throws IOException {
        File outFile = new File(getTargetCacheDir(), "testdata/" + assetPath);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create parent dir for " + outFile);
        }
        copyAssetFile(assetPath, outFile);
        return outFile;
    }

    /** Copy an asset directory (recursively) into the target app cache and return it. */
    public static File extractDirectory(String assetDir) throws IOException {
        File dest = new File(getTargetCacheDir(), "testdata/" + assetDir);
        deleteRecursively(dest);
        copyAssetRecursively(assetDir, dest);
        return dest;
    }

    private static void copyAssetRecursively(String assetPath, File dest) throws IOException {
        AssetManager am = getAssetManager();
        String[] children = am.list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetFile(assetPath, dest);
            return;
        }
        if (!dest.exists() && !dest.mkdirs()) {
            throw new IOException("Failed to create " + dest);
        }
        for (String child : children) {
            String childAssetPath = assetPath.isEmpty() ? child : assetPath + "/" + child;
            File childDest = new File(dest, child);
            copyAssetRecursively(childAssetPath, childDest);
        }
    }

    private static void copyAssetFile(String assetPath, File dest) throws IOException {
        AssetManager am = getAssetManager();
        try (InputStream is = am.open(assetPath);
             OutputStream os = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) >= 0) {
                os.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("Failed to delete " + file);
        }
    }

    private static File getTargetCacheDir() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return target.getCacheDir();
    }

    private static AssetManager getAssetManager() {
        return InstrumentationRegistry.getInstrumentation().getContext().getAssets();
    }
}
