package org.osservatorionessuno.bugbane.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import android.util.Log;

import java.net.InetAddress;

import io.github.muntashirakon.adb.android.AdbMdns;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import org.osservatorionessuno.bugbane.R;

public class AdbPairingService extends Service {
    public static final String NOTIFICATION_CHANNEL = "adb_pairing";
    public static final String ACTION_PAIRING_RESULT = "org.osservatorionessuno.bugbane.PAIRING_RESULT";
    public static final String EXTRA_SUCCESS = "success";
    public static final String EXTRA_ERROR_MESSAGE = "error_message";

    private static final String TAG = "AdbPairingService";
    private static final int NOTIFICATION_ID = 1;
    private static final int REPLY_REQUEST_ID = 1;
    private static final int STOP_REQUEST_ID = 2;
    private static final String START_ACTION = "start";
    private static final String STOP_ACTION = "stop";
    private static final String REPLY_ACTION = "reply";
    private static final String REMOTE_INPUT_RESULT_KEY = "pairing_code";
    private static final String PORT_KEY = "port";

    public static Intent startIntent(Context context) {
        return new Intent(context, AdbPairingService.class).setAction(START_ACTION);
    }

    public static Intent stopIntent(Context context) {
        return new Intent(context, AdbPairingService.class).setAction(STOP_ACTION);
    }

    private static Intent replyIntent(Context context, int port) {
        return new Intent(context, AdbPairingService.class).setAction(REPLY_ACTION).putExtra(PORT_KEY, port);
    }

    private AdbMdns mAdbMdns;
    private boolean started = false;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel_adb_pairing),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        channel.setAllowBubbles(false);
        nm.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        Notification notification = null;
        if (intent != null) {
            String action = intent.getAction();
            if (START_ACTION.equals(action)) {
                notification = onStart();
            } else if (REPLY_ACTION.equals(action)) {
                CharSequence code = RemoteInput.getResultsFromIntent(intent) != null ?
                        RemoteInput.getResultsFromIntent(intent).getCharSequence(REMOTE_INPUT_RESULT_KEY) : "";
                int port = intent.getIntExtra(PORT_KEY, -1);
                if (port != -1) {
                    notification = onInput(code != null ? code.toString() : "", port);
                } else {
                    notification = onStart();
                }
            } else if (STOP_ACTION.equals(action)) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
        if (notification != null) {
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } catch (Throwable th) {
                Log.e(TAG, "startForeground failed", th);
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
            }
        }
        return START_REDELIVER_INTENT;
    }

    private void startSearch() {
        if (started) return;
        started = true;
        mAdbMdns = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (InetAddress host, int port) -> {
            Log.i(TAG, "Pairing service port: " + port);
            if (port <= 0) return;
            Notification notification = createInputNotification(port);
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
        });
        mAdbMdns.start();
    }

    private void stopSearch() {
        if (!started) return;
        started = false;
        if (mAdbMdns != null) {
            mAdbMdns.stop();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopSearch();
    }

    private Notification onStart() {
        startSearch();
        return searchingNotification();
    }

    private Notification onInput(String code, int port) {
        new Thread(() -> {
            try {
                AbsAdbConnectionManager manager = AdbConnectionManager.getInstance(getApplication());
                String host = "127.0.0.1";
                boolean success = manager.pair(host, port, code);
                handleResult(success, null);
            } catch (Throwable th) {
                Log.w(TAG, "pair failed", th);
                handleResult(false, th.getMessage());
            }
        }).start();
        return workingNotification();
    }

    private void handleResult(boolean success, String errorMessage) {
        // Broadcast the result
        Intent resultIntent = new Intent(ACTION_PAIRING_RESULT).setPackage(getPackageName());
        resultIntent.putExtra(EXTRA_SUCCESS, success);
        if (errorMessage != null) {
            resultIntent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage);
        }
        sendBroadcast(resultIntent);
        
        // Update notification
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        String title = success ? getString(R.string.notification_adb_pairing_succeed_title)
                : getString(R.string.notification_adb_pairing_failed_title);
        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_bugbane_zoom)
                .setContentTitle(title)
                .build();
        nm.notify(NOTIFICATION_ID, notification);
        stopSelf();
    }

    private PendingIntent stopPendingIntent() {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0;
        return PendingIntent.getService(this, STOP_REQUEST_ID, stopIntent(this), flags);
    }

    private PendingIntent replyPendingIntent(int port) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getForegroundService(this, REPLY_REQUEST_ID, replyIntent(this, port), flags);
    }

    private Notification.Action replyAction(int port) {
        RemoteInput remoteInput = new RemoteInput.Builder(REMOTE_INPUT_RESULT_KEY)
                .setLabel(getString(R.string.notification_adb_pairing_input_code))
                .build();
        return new Notification.Action.Builder(null,
                getString(R.string.notification_adb_pairing_input_code),
                replyPendingIntent(port)).addRemoteInput(remoteInput).build();
    }

    private Notification searchingNotification() {
        return new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_bugbane_zoom)
                .setContentTitle(getString(R.string.notification_adb_pairing_searching_for_service_title))
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notification_adb_pairing_stop_searching),
                        stopPendingIntent()).build())
                .build();
    }

    private Notification createInputNotification(int port) {
        return new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_bugbane_zoom)
                .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title, port))
                .addAction(replyAction(port))
                .build();
    }

    private Notification workingNotification() {
        return new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_bugbane_zoom)
                .setContentTitle(getString(R.string.notification_adb_pairing_working_title))
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
