package org.osservatorionessuno.bugbane.utils;

import android.app.Application;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.LocalServices;
import io.github.muntashirakon.adb.android.AdbMdns;
import io.github.muntashirakon.adb.android.AndroidUtils;

public class AdbViewModel extends AndroidViewModel {
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final MutableLiveData<Boolean> connectAdb = new MutableLiveData<>();
    private final MutableLiveData<Boolean> pairAdb = new MutableLiveData<>();
    private final MutableLiveData<Boolean> askPairAdb = new MutableLiveData<>();
    private final MutableLiveData<CharSequence> commandOutput = new MutableLiveData<>();
    private final MutableLiveData<Integer> pairingPort = new MutableLiveData<>();

    private String mPairingHost;
    private int mPairingPort = -1;

    @Nullable
    private AdbStream adbShellStream;

    public AdbViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<Boolean> watchConnectAdb() {
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

    @Override
    protected void onCleared() {
        super.onCleared();
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
                AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
                boolean connectionStatus;
                try {
                    connectionStatus = manager.connect(AndroidUtils.getHostIpAddress(getApplication()), port);
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
                AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
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

            AdbMdns adbMdns = new AdbMdns(getApplication(), AdbMdns.SERVICE_TYPE_TLS_PAIRING, (hostAddress, port) -> {
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
                    AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
                    String host = mPairingHost != null ? mPairingHost : AndroidUtils.getHostIpAddress(getApplication());
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
            AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
            boolean connected = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    connected = manager.connectTls(getApplication(), 5000);
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
                    AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
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

    public void runQuickForensics() {
        executor.submit(() -> {
            try {
                AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
                // TODO: Implement QuickForensics functionality
                // new org.osservatorionessuno.bugbane.qf.QuickForensics()
                //         .run(getApplication(), manager);
                System.out.println("QuickForensics functionality not yet implemented");
                // Post message to main thread via LiveData instead of Toast
                commandOutput.postValue("QuickForensics functionality not yet implemented");
            } catch (Exception e) {
                e.printStackTrace();
                commandOutput.postValue("Error running QuickForensics: " + e.getMessage());
            }
        });
    }
}
