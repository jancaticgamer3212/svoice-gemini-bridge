package com.svoice.gemini;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Ui {
    private static final String TAG = "SVoiceGeminiFrida";

    private Ui() {}

    static void setState(Activity activity, String stateName) {
        if (activity == null) return;
        try {
            ClassLoader cl = activity.getClass().getClassLoader();
            Class<?> conversation = Class.forName("com.vlingo.midas.gui.ConversationActivity", false, cl);
            Object control = staticField(conversation, "controlFragment");
            if (control == null) return;
            Class<?> stateClass = Class.forName("com.vlingo.midas.gui.ControlFragment$ControlState", false, cl);
            Object state = enumValue(stateClass, stateName);
            if (state == null) return;
            Method m = findMethod(control.getClass(), "setState", stateClass);
            if (m == null) return;
            m.setAccessible(true);
            m.invoke(control, state);
        } catch (Throwable t) {
            Log.w(TAG, "setState(" + stateName + ") failed: " + t);
        }
    }

    static boolean bubble(Activity activity, String typeName, String message) {
        if (activity == null || message == null || message.length() == 0) return false;
        try {
            Object dialog = instanceField(activity, "dialogFragment");
            if (dialog == null) return false;
            ClassLoader cl = activity.getClass().getClassLoader();
            Class<?> typeClass = Class.forName("com.vlingo.midas.gui.DialogBubble$BubbleType", false, cl);
            Object type = enumValue(typeClass, typeName);
            if (type == null) return false;
            Method m = findMethod(dialog.getClass(), "addDialogBubble",
                    typeClass, String.class, boolean.class, boolean.class);
            if (m == null) return false;
            m.setAccessible(true);
            m.invoke(dialog, type, message, Boolean.TRUE, Boolean.FALSE);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "addDialogBubble(" + typeName + ") failed: " + t);
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
            Method m = findMethod(activity.getClass(), "retireLastWidget");
            if (m != null) {
                m.setAccessible(true);
                m.invoke(activity);
            }
        } catch (Throwable ignored) {
        }
    }

    static void notifyRecorderClosed(Activity activity) {
        if (activity == null) return;
        try {
            Method m = findMethod(activity.getClass(), "onASRRecorderClosed");
            if (m != null) {
                m.setAccessible(true);
                m.invoke(activity);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object enumValue(Class<?> enumClass, String name) {
        Object[] constants = enumClass.getEnumConstants();
        if (constants == null) return null;
        for (Object c : constants) {
            if (c instanceof Enum && ((Enum<?>) c).name().equals(name)) return c;
        }
        return null;
    }

    private static Object staticField(Class<?> owner, String name) throws Exception {
        Field f = findField(owner, name);
        if (f == null) return null;
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object instanceField(Object target, String name) throws Exception {
        Field f = findField(target.getClass(), name);
        if (f == null) return null;
        f.setAccessible(true);
        return f.get(target);
    }

    private static Field findField(Class<?> start, String name) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> start, String name, Class<?>... params) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            for (Class<?> iface : c.getInterfaces()) {
                try {
                    return iface.getDeclaredMethod(name, params);
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return null;
    }
}
