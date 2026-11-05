package com.svoice.gemini;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.InputStream;

/**
 * Keeps the Frida agent attached to S Voice. The agent lives inside S Voice's process, so it
 * dies whenever that process does; this service runs a root shell loop that reattaches.
 */
public class WatchdogService extends Service {
    private static final String TAG = "SVoiceGeminiWatchdog";
    private static final String DIR = "/data/local/tmp/svoice-gemini";
    private static final int NOTIFICATION_ID = 41;

    private volatile boolean running;
    private Thread worker;

    public static void start(Context context) {
        context.startService(new Intent(context, WatchdogService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(getString(R.string.watchdog_title))
                .setContentText(getString(R.string.watchdog_text))
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "svoice-watchdog");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loop() {
        File script = new File(DIR, "watchdog.sh");
        while (running) {
            if (!script.isFile()) {
                Log.w(TAG, "missing " + script + "; push the payload with frida/push-and-run.sh");
                sleep(30000);
                continue;
            }
            try {
                Process process = new ProcessBuilder("su", "-c", "sh " + script.getAbsolutePath())
                        .redirectErrorStream(true)
                        .start();
                drain(process.getInputStream());
                Log.i(TAG, "root watchdog exited with " + process.waitFor());
            } catch (Exception e) {
                Log.w(TAG, "cannot run the root watchdog: " + e);
            }
            sleep(10000);
        }
    }

    private void drain(InputStream stream) {
        byte[] buffer = new byte[4096];
        try {
            while (stream.read(buffer) != -1) {
                // the shell writes its own log files
            }
        } catch (Exception ignored) {
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
