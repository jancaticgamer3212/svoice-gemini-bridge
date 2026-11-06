package com.svoice.gemini;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.telephony.SmsManager;

import org.json.JSONObject;

/** Performs the phone actions the model may ask for. Runs with the caller's permissions. */
final class DeviceActions {
    private DeviceActions() {
    }

    /** Executes an action object; returns a short line for the log, or throws with a spoken reason. */
    static String perform(Context ctx, JSONObject action) throws Exception {
        String name = action.optString("name", "").trim().toLowerCase();
        if (name.length() == 0) return null;
        if ("call".equals(name)) return call(ctx, action);
        if ("sms".equals(name)) return sms(ctx, action);
        if ("alarm".equals(name)) return alarm(ctx, action);
        if ("timer".equals(name)) return timer(ctx, action);
        throw new Exception("Unknown action: " + name);
    }

    private static String call(Context ctx, JSONObject action) throws Exception {
        String number = number(ctx, action);
        Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
            return "calling " + number;
        } catch (SecurityException e) {
            Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
            dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(dial);
            return "dialer opened with " + number;
        }
    }

    private static String sms(Context ctx, JSONObject action) throws Exception {
        String number = number(ctx, action);
        String message = action.optString("message", "").trim();
        if (message.length() == 0) throw new Exception("The message text was empty.");
        try {
            SmsManager.getDefault().sendTextMessage(number, null, message, null, null);
            return "text sent to " + number;
        } catch (Throwable t) {
            Intent compose = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
            compose.putExtra("sms_body", message);
            compose.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(compose);
            return "message opened for " + number;
        }
    }

    private static String alarm(Context ctx, JSONObject action) throws Exception {
        int hour = action.optInt("hour", -1);
        int minute = action.optInt("minute", -1);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new Exception("I need a valid time for the alarm.");
        }
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
        intent.putExtra(AlarmClock.EXTRA_HOUR, hour);
        intent.putExtra(AlarmClock.EXTRA_MINUTES, minute);
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        String label = action.optString("label", "").trim();
        if (label.length() > 0) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(ctx, intent, "I could not set the alarm on this phone.");
        return String.format("alarm set for %02d:%02d", hour, minute);
    }

    private static String timer(Context ctx, JSONObject action) throws Exception {
        int seconds = action.optInt("seconds", -1);
        if (seconds <= 0) throw new Exception("I need a length for the timer.");
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER);
        intent.putExtra(AlarmClock.EXTRA_LENGTH, seconds);
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        String label = action.optString("label", "").trim();
        if (label.length() > 0) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(ctx, intent, "I could not set the timer on this phone.");
        return "timer set for " + seconds + " seconds";
    }

    private static void start(Context ctx, Intent intent, String reason) throws Exception {
        try {
            ctx.startActivity(intent);
        } catch (SecurityException e) {
            throw new Exception(reason);
        }
    }

    private static String number(Context ctx, JSONObject action) throws Exception {
        String number = action.optString("number", "").trim();
        if (number.length() == 0) {
            String contact = action.optString("contact", "").trim();
            if (contact.length() > 0) number = lookup(ctx, contact);
        }
        if (number.length() == 0) {
            throw new Exception("I could not find a phone number for that contact.");
        }
        return number.replaceAll("[\\s()-]", "");
    }

    private static String lookup(Context ctx, String contact) {
        String[] projection = {ContactsContract.CommonDataKinds.Phone.NUMBER};
        String selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ?";
        Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection, selection, new String[]{contact}, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
            cursor = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + contact + "%"}, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return "";
    }
}
