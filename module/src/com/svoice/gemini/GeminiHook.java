package com.svoice.gemini;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class GeminiHook implements IXposedHookLoadPackage {
    private static final String PACKAGE = "com.vlingo.midas";
    private static final String WAKE_STREAM = "com.vlingo.midas.gui.ControlFragment$3";

    private static volatile WeakReference<Activity> sActivity = new WeakReference<Activity>(null);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PACKAGE.equals(lpparam.packageName)) return;

        final ClassLoader cl = lpparam.classLoader;

        try {
            XposedHelpers.findAndHookMethod("com.vlingo.midas.gui.ConversationActivity", cl,
                    "onCreate", Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sActivity = new WeakReference<Activity>((Activity) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("S Voice Gemini: onCreate hook failed");
            XposedBridge.log(t);
        }

        try {
            XposedHelpers.findAndHookMethod("com.vlingo.midas.gui.ConversationActivity", cl,
                    "onResume", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sActivity = new WeakReference<Activity>((Activity) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("S Voice Gemini: onResume hook failed");
            XposedBridge.log(t);
        }

        Class<?> mode;
        Class<?> wakeContext;
        Class<?> audioStream;
        try {
            mode = XposedHelpers.findClass("com.vlingo.core.facade.dialogflow.RecognitionMode", cl);
            wakeContext = XposedHelpers.findClass("com.vlingo.core.internal.dialogmanager.WakeUpContext", cl);
            audioStream = XposedHelpers.findClass("com.vlingo.core.api.audio.IAudioStream", cl);
        } catch (Throwable t) {
            XposedBridge.log("S Voice Gemini: S Voice classes not found, module inactive");
            XposedBridge.log(t);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod("com.vlingo.core.internal.dialogmanager.DialogFlow", cl,
                    "startUserFlow", mode, wakeContext, audioStream, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                interceptVoice(param);
                            } catch (Throwable t) {
                                XposedBridge.log(t);
                            }
                        }
                    });
            XposedBridge.log("S Voice Gemini: voice hook installed");
        } catch (Throwable t) {
            XposedBridge.log("S Voice Gemini: voice hook failed");
            XposedBridge.log(t);
        }

        try {
            XposedHelpers.findAndHookMethod("com.vlingo.core.internal.dialogmanager.DialogFlow", cl,
                    "startUserFlow", String.class, Object.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                interceptText(param);
                            } catch (Throwable t) {
                                XposedBridge.log(t);
                            }
                        }
                    });
            XposedBridge.log("S Voice Gemini: text hook installed");
        } catch (Throwable t) {
            XposedBridge.log("S Voice Gemini: text hook failed");
            XposedBridge.log(t);
        }
    }

    private static void interceptVoice(XC_MethodHook.MethodHookParam param) {
        Object stream = param.args[2];
        if (stream != null && WAKE_STREAM.equals(stream.getClass().getName())) {
            // "Hi Galaxy" uses Samsung's wake-up recorder, which this module does not
            // take over. Leave that path alone instead of fighting it for the mic.
            XposedBridge.log("S Voice Gemini: wake-word turn left to the original code");
            return;
        }

        Settings cfg = Settings.load();
        if (!cfg.enabled) return;

        if (stream != null) {
            try {
                XposedHelpers.callMethod(stream, "close");
            } catch (Throwable ignored) {
            }
        }
        param.setResult(Boolean.TRUE);

        Activity activity = activity();
        Context app = appContext(activity);
        if (app == null) {
            XposedBridge.log("S Voice Gemini: no context available for voice turn");
            return;
        }
        TurnController.startVoice(activity, app, cfg);
    }

    private static void interceptText(XC_MethodHook.MethodHookParam param) {
        String text = (String) param.args[0];
        if (text == null) return;
        text = text.trim();
        if (text.length() == 0) return;

        Settings cfg = Settings.load();
        if (!cfg.enabled) return;

        param.setResult(Boolean.TRUE);

        Activity activity = activity();
        Context app = appContext(activity);
        if (app == null) {
            XposedBridge.log("S Voice Gemini: no context available for text turn");
            return;
        }
        TurnController.startText(activity, app, cfg, text);
    }

    private static Activity activity() {
        WeakReference<Activity> ref = sActivity;
        Activity a = ref == null ? null : ref.get();
        if (a != null && a.isFinishing()) return null;
        return a;
    }

    private static Context appContext(Activity activity) {
        if (activity != null) return activity.getApplicationContext();
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app != null) return app;
        } catch (Throwable ignored) {
        }
        return null;
    }
}
