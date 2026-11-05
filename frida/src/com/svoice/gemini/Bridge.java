package com.svoice.gemini;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Entry points called from the Frida script. This class has no Xposed dependency; it is loaded
 * into S Voice's process from the bridge APK with a DexClassLoader whose parent is S Voice's
 * own class loader.
 */
public final class Bridge {
    private static final String TAG = "SVoiceGeminiFrida";
    private static final String WAKE_STREAM = "com.vlingo.midas.gui.ControlFragment$3";

    private static volatile WeakReference<Activity> sActivity = new WeakReference<Activity>(null);
    private static volatile Context sApp;

    private Bridge() {}

    public static void init(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        sApp = app != null ? app : context;
        Log.i(TAG, "bridge initialized");
    }

    public static void setActivity(Activity activity) {
        if (activity == null) return;
        sActivity = new WeakReference<Activity>(activity);
        Context app = activity.getApplicationContext();
        if (app != null) sApp = app;
    }

    public static boolean isEnabled() {
        return Settings.load().enabled;
    }

    /** Returns true when this turn was taken over and the original Vlingo flow must be skipped. */
    public static boolean onVoice(Object stream) {
        if (stream != null && WAKE_STREAM.equals(stream.getClass().getName())) {
            Log.i(TAG, "wake-word turn left to S Voice");
            return false;
        }
        Settings cfg = Settings.load();
        if (!cfg.enabled) return false;

        Context app = appContext();
        if (app == null) {
            Log.w(TAG, "no application context; leaving turn to S Voice");
            return false;
        }

        closeQuietly(stream);
        TurnController.startVoice(activity(), app, cfg);
        return true;
    }

    /** Returns true when the text turn was taken over. */
    public static boolean onText(String text) {
        if (text == null) return false;
        text = text.trim();
        if (text.length() == 0) return false;

        Settings cfg = Settings.load();
        if (!cfg.enabled) return false;

        Context app = appContext();
        if (app == null) {
            Log.w(TAG, "no application context; leaving turn to S Voice");
            return false;
        }

        TurnController.startText(activity(), app, cfg, text);
        return true;
    }

    private static Activity activity() {
        WeakReference<Activity> ref = sActivity;
        Activity a = ref == null ? null : ref.get();
        if (a != null && !a.isFinishing()) return a;
        return findConversationActivity();
    }

    private static Context appContext() {
        Context app = sApp;
        if (app != null) return app;
        Activity a = activity();
        if (a != null) {
            Context c = a.getApplicationContext();
            if (c != null) {
                sApp = c;
                return c;
            }
        }
        return null;
    }

    private static void closeQuietly(Object stream) {
        if (stream == null) return;
        try {
            Method m = stream.getClass().getMethod("close");
            m.setAccessible(true);
            m.invoke(stream);
        } catch (Throwable t) {
            Log.w(TAG, "closing S Voice stream failed: " + t);
        }
    }

    private static Activity findConversationActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread == null) return null;
            Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object mapObj = f.get(thread);
            if (!(mapObj instanceof Map)) return null;
            for (Object record : ((Map<?, ?>) mapObj).values()) {
                if (record == null) continue;
                try {
                    Field af = record.getClass().getDeclaredField("activity");
                    af.setAccessible(true);
                    Object act = af.get(record);
                    if (act instanceof Activity) {
                        Activity a = (Activity) act;
                        if (!a.isFinishing() && "com.vlingo.midas.gui.ConversationActivity"
                                .equals(a.getClass().getName())) {
                            return a;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "activity lookup failed: " + t);
        }
        return null;
    }
}
