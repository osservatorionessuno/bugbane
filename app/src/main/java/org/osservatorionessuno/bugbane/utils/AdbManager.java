package org.osservatorionessuno.bugbane.utils;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.LocalServices;
import io.github.muntashirakon.adb.android.AdbMdns;
import io.github.muntashirakon.adb.android.AndroidUtils;

public class AdbManager {
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final MutableLiveData<Boolean> connectAdb = new MutableLiveData<>();
    private final MutableLiveData<Boolean> pairAdb = new MutableLiveData<>();
    private final MutableLiveData<Boolean> askPairAdb = new MutableLiveData<>();
    private final MutableLiveData<CharSequence> commandOutput = new MutableLiveData<>();
    private final MutableLiveData<Integer> pairingPort = new MutableLiveData<>();

    private Future<?> qfFuture;
    private final AtomicBoolean qfCancelled = new AtomicBoolean(false);

    private String mPairingHost;
    private int mPairingPort = -1;

    private Context appContext = null;

    @Nullable
    private AdbStream adbShellStream;

    public AdbManager(@NonNull Context applicationContext) {
        this.appContext = applicationContext;
    }

    public MutableLiveData<Boolean> watchConnectAdb() {
        return connectAdb;
    }

    public LiveData<Boolean> watchPairAdb() {
        return pairAdb;
    }

    public LiveData<Boolean> watchAskPairAdb() {
        return askPairAdb;
    }

    public LiveData<CharSequence> watchCommandOutput() {
        return commandOutput;
    }

    public LiveData<Integer> watchPairingPort() {
        return pairingPort;
    }

    public void handleOnCleared() {
        executor.submit(() -> {
            try {
                if (adbShellStream != null) {
                    adbShellStream.close();
                    adbShellStream = null;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        executor.shutdown();
    }

    public void connect(int port) {
        executor.submit(() -> {
            try {
                AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(this.appContext);
                boolean connectionStatus;
                try {
                    connectionStatus = manager.connect(AndroidUtils.getHostIpAddress(this.appContext), port);
                } catch (Throwable th) {
                    th.printStackTrace();
                    connectionStatus = false;
                }
                connectAdb.postValue(connectionStatus);
            } catch (Throwable th) {
                th.printStackTrace();
                connectAdb.postValue(false);
            }
        });
    }

    public void autoConnect() {
        executor.submit(this::autoConnectInternal);
    }

    public void disconnect() {
        executor.submit(() -> {
            try {
                AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(this.appContext);
                manager.disconnect();
                connectAdb.postValue(false);
            } catch (Throwable th) {
                th.printStackTrace();
                connectAdb.postValue(true);
            }
        });
    }

    public void getPairingPort() {
        executor.submit(() -> {
            AtomicInteger atomicPort = new AtomicInteger(-1);
            final String[] host = {null};
            CountDownLatch resolveHostAndPort = new CountDownLatch(1);

            AdbMdns adbMdns = new AdbMdns(this.appContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (hostAddress, port) -> {
                atomicPort.set(port);
                if (hostAddress != null) {
                    host[0] = hostAddress.getHostAddress();
                }
                resolveHostAndPort.countDown();
            });
            adbMdns.start();

            try {
                if (!resolveHostAndPort.await(1, TimeUnit.MINUTES)) {
                    return;
                }
            } catch (InterruptedException ignore) {
            } finally {
                adbMdns.stop();
            }

            mPairingPort = atomicPort.get();
            mPairingHost = host[0];
            pairingPort.postValue(mPairingPort);
        });
    }

    public void pair(int port, String pairingCode) {
        executor.submit(() -> {
            try {
                boolean pairingStatus;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(this.appContext);
                    String host = mPairingHost != null ? mPairingHost : AndroidUtils.getHostIpAddress(this.appContext);
                    int p = port > 0 ? port : mPairingPort;
                    pairingStatus = manager.pair(host, p, pairingCode);
                } else pairingStatus = false;
                pairAdb.postValue(pairingStatus);
                autoConnectInternal();
            } catch (Throwable th) {
                th.printStackTrace();
                pairAdb.postValue(false);
            }
        });
    }

    @WorkerThread
    private void autoConnectInternal() {
        try {
            AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(this.appContext);
            boolean connected = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    connected = manager.connectTls(this.appContext, 5000);
                } catch (AdbPairingRequiredException | InterruptedException ie) {
                    askPairAdb.postValue(true);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }
            if (connected) {
                connectAdb.postValue(true);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private volatile boolean clearEnabled;
    private final Runnable outputGenerator = () -> {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(adbShellStream.openInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = reader.readLine()) != null) {
                if (clearEnabled) {
                    sb.delete(0, sb.length());
                    clearEnabled = false;
                }
                sb.append(s).append("\n");
                commandOutput.postValue(sb);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    };

    public void execute(String command) {
        executor.submit(() -> {
            try {
                if (adbShellStream == null || adbShellStream.isClosed()) {
                    AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(this.appContext);
                    adbShellStream = manager.openStream(LocalServices.SHELL);
                    new Thread(outputGenerator).start();
                }
                if (command.equals("clear")) {
                    clearEnabled = true;
                }
                try (OutputStream os = adbShellStream.openOutputStream()) {
                    os.write(String.format("%1$s\n", command).getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.write("\n".getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                e.printStackTrace();
                askPairAdb.postValue(true);
            }
        });
    }

    public synchronized boolean isQuickForensicsRunning() {
        return qfFuture != null && !qfFuture.isDone();
    }

    public synchronized boolean isQuickForensicsCancelled() {
        return qfCancelled.get();
    }

    public synchronized void cancelQuickForensics() {
        qfCancelled.set(true);
    }

    public void runQuickForensics(@NonNull File baseDir,
                                  @NonNull org.osservatorionessuno.bugbane.qf.QuickForensics.ProgressListener listener) {
        if (isQuickForensicsRunning()) {
            return;
        }
        qfCancelled.set(false);
        qfFuture = executor.submit(() -> {
            try {
                AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(this.appContext);
                File out = new org.osservatorionessuno.bugbane.qf.QuickForensics()
                        .run(this.appContext, manager, baseDir, listener);
                if (qfCancelled.get()) {
                    commandOutput.postValue("QuickForensics cancelled");
                } else {
                    commandOutput.postValue("QuickForensics completed: " + out.getAbsolutePath());
                }
            } catch (Exception e) {
                e.printStackTrace();
                commandOutput.postValue("Error running QuickForensics: " + e.getMessage());
            }
        });
    }
}
