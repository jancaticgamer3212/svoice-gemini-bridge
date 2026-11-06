package com.svoice.gemini;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

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

    /**
     * Shows S Voice's own countdown card. The stock flow only starts the countdown from the
     * card's add animation, and its initialize() resets the clock app timer; neither fits a
     * turn answered from the bridge, so the card is rendered from TimerManager and the
     * countdown is driven directly.
     */
    static boolean timerCard(Activity activity, long millis) {
        if (activity == null || millis < 1000L) return false;
        final long max = 99L * 60L * 60L * 1000L;
        if (millis > max) return false;
        try {
            if (instanceField(activity, "contentFragment") == null) return false;
            ClassLoader cl = activity.getClass().getClassLoader();
            Class<?> conversation = Class.forName("com.vlingo.midas.gui.ConversationActivity", false, cl);
            Class<?> widgetClass = Class.forName("com.vlingo.midas.gui.Widget", false, cl);
            Class<?> builderClass = Class.forName("com.vlingo.midas.gui.WidgetBuilder", false, cl);
            Class<?> managerClass = Class.forName("com.vlingo.midas.gui.timer.TimerManager", false, cl);
            Class<?> timerWidgetClass = Class.forName("com.vlingo.midas.gui.widgets.TimerWidget", false, cl);
            Class<?> counterClass = Class.forName("android.os.CountDownTimer", false, cl);
            Class<?> keyClass = Class.forName(
                    "com.vlingo.core.internal.dialogmanager.util.WidgetUtil$WidgetKey", false, cl);
            Class<?> decorClass = Class.forName(
                    "com.vlingo.core.internal.dialogmanager.WidgetDecorator", false, cl);

            Object key = enumValue(keyClass, "SetTimer");
            Method showCmd = decorClass.getDeclaredMethod("makeTimerShowCmd");
            showCmd.setAccessible(true);
            Object decor = showCmd.invoke(null);
            if (key == null || decor == null) return false;

            long seconds = millis / 1000L;
            long exact = seconds * 1000L;
            int hours = (int) (seconds / 3600L);
            int minutes = (int) ((seconds / 60L) % 60L);
            int secs = (int) (seconds % 60L);

            Object running = staticField(managerClass, "timer");
            if (counterClass.isInstance(running)) counterClass.getMethod("cancel").invoke(running);

            // Seed the manager before inflation: onFinishInflate paints the card from it.
            setStatic(managerClass, "inputMillis", exact);
            setStatic(managerClass, "remainMillis", exact);
            setStatic(managerClass, "hour", hours);
            setStatic(managerClass, "minute", minutes);
            setStatic(managerClass, "second", secs);
            setStatic(timerWidgetClass, "needStartTimer", Boolean.FALSE);

            Class<?> listenerClass = Class.forName(
                    "com.vlingo.core.internal.dialogmanager.vvs.WidgetActionListener", false, cl);
            Object listener = Proxy.newProxyInstance(cl, new Class<?>[]{listenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return Boolean.FALSE;
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            if (type == float.class) return 0f;
                            if (type == double.class) return 0d;
                            if (type == short.class) return (short) 0;
                            if (type == byte.class) return (byte) 0;
                            if (type == char.class) return (char) 0;
                            return null;
                        }
                    });

            Constructor<?> ctor = builderClass.getDeclaredConstructor(conversation);
            ctor.setAccessible(true);
            Object builder = ctor.newInstance(activity);

            // An empty time string keeps initialize() from resetting, which would broadcast
            // TIMER_RESET and stop the timer the clock app has already started.
            Method build = builderClass.getDeclaredMethod("buildWidget",
                    keyClass, decorClass, Object.class, listenerClass);
            build.setAccessible(true);
            Object widget = build.invoke(builder, key, decor, "", listener);
            if (widget == null) return false;

            Method add = findMethod(conversation, "addWidget", widgetClass);
            if (add == null) return false;
            add.setAccessible(true);
            add.invoke(activity, widget);

            try {
                setStatic(timerWidgetClass, "needStartTimer", Boolean.FALSE);
                Method setState = findMethod(managerClass, "setState", int.class);
                setState.setAccessible(true);
                setState.invoke(null, 1);
                Method setTimer = timerWidgetClass.getDeclaredMethod("setTimer", long.class);
                setTimer.setAccessible(true);
                setTimer.invoke(null, exact);
                Object started = staticField(managerClass, "timer");
                if (counterClass.isInstance(started)) counterClass.getMethod("start").invoke(started);
            } catch (Throwable t) {
                Log.w(TAG, "timer card shown without its countdown: " + t);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "timerCard failed: " + t);
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

    private static void setStatic(Class<?> owner, String name, Object value) throws Exception {
        Field f = findField(owner, name);
        if (f == null) throw new NoSuchFieldException(owner.getName() + "." + name);
        f.setAccessible(true);
        f.set(null, value);
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
