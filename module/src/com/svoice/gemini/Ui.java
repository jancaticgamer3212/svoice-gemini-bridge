package com.svoice.gemini;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import de.robv.android.xposed.XposedHelpers;

final class Ui {
    private static final String TAG = "SVoiceGemini";

    private Ui() {}

    static void setState(Activity activity, String stateName) {
        if (activity == null) return;
        try {
            ClassLoader cl = activity.getClass().getClassLoader();
            Class<?> conversation = XposedHelpers.findClass("com.vlingo.midas.gui.ConversationActivity", cl);
            Object control = XposedHelpers.getStaticObjectField(conversation, "controlFragment");
            if (control == null) return;
            Class<?> stateClass = XposedHelpers.findClass("com.vlingo.midas.gui.ControlFragment$ControlState", cl);
            Object state = XposedHelpers.getStaticObjectField(stateClass, stateName);
            XposedHelpers.callMethod(control, "setState", state);
        } catch (Throwable t) {
            Log.w(TAG, "setState(" + stateName + ") failed", t);
        }
    }

    static boolean bubble(Activity activity, String typeName, String message) {
        if (activity == null || message == null || message.length() == 0) return false;
        try {
            Object dialog = XposedHelpers.getObjectField(activity, "dialogFragment");
            if (dialog == null) return false;
            ClassLoader cl = activity.getClass().getClassLoader();
            Class<?> typeClass = XposedHelpers.findClass("com.vlingo.midas.gui.DialogBubble$BubbleType", cl);
            Object type = XposedHelpers.getStaticObjectField(typeClass, typeName);
            XposedHelpers.callMethod(dialog, "addDialogBubble", type, message, true, false);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "addDialogBubble(" + typeName + ") failed", t);
            return false;
        }
    }

    static void toast(Context ctx, String message) {
        if (ctx == null) return;
        try {
            Toast.makeText(ctx.getApplicationContext(), message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    static void retireLastWidget(Activity activity) {
        if (activity == null) return;
        try {
            XposedHelpers.callMethod(activity, "retireLastWidget");
        } catch (Throwable ignored) {
        }
    }

    static void notifyRecorderClosed(Activity activity) {
        if (activity == null) return;
        try {
            XposedHelpers.callMethod(activity, "onASRRecorderClosed");
        } catch (Throwable ignored) {
        }
    }
}
