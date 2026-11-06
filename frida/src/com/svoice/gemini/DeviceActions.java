package com.svoice.gemini;

import android.app.SearchManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Performs the phone actions the model may ask for. Runs with the caller's permissions. */
final class DeviceActions {
    private static final Pattern DURATION =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b");
    private static final Pattern CLOCK =
            Pattern.compile("^(\\d{1,2})(?::(\\d{1,2}))?\\s*(am|pm)?$");

    private DeviceActions() {
    }

    /** What an action produced: a log line and, optionally, text that replaces the spoken answer. */
    static final class Result {
        final String log;
        final String say;

        Result(String log) {
            this(log, null);
        }

        Result(String log, String say) {
            this.log = log == null ? "" : log;
            this.say = say;
        }
    }

    static Result perform(Context ctx, JSONObject action) throws Exception {
        String name = normalize(action.optString("name", ""));
        if (name.length() == 0) return null;
        if (is(name, "call", "phonecall", "dial", "makecall", "callcontact")) return call(ctx, action);
        if (is(name, "sms", "text", "textmessage", "sendtext", "message", "sendmessage", "sendsms"))
            return sms(ctx, action);
        if (is(name, "email", "sendemail", "mail", "sendmail")) return email(ctx, action);
        if (is(name, "alarm", "setalarm", "wakeup")) return alarm(ctx, action);
        if (is(name, "timer", "settimer", "countdown")) return timer(ctx, action);
        if (is(name, "event", "calendarevent", "addevent", "createevent", "meeting", "appointment"))
            return event(ctx, action);
        if (is(name, "open", "openapp", "launch", "launchapp", "startapp", "runapp"))
            return openApp(ctx, action);
        if (is(name, "search", "websearch", "google", "browse", "lookup")) return search(ctx, action);
        if (is(name, "navigate", "directions", "map", "maps", "route")) return navigate(ctx, action);
        if (is(name, "music", "playmusic", "play", "song", "playlist", "artist"))
            return playMusic(ctx, action);
        if (is(name, "media", "mediacontrol", "playpause", "pause", "resume", "next", "previous", "stop"))
            return media(ctx, action);
        if (is(name, "photo", "takephoto", "camera", "picture", "selfie")) return photo(ctx, false);
        if (is(name, "video", "recordvideo")) return photo(ctx, true);
        if (is(name, "contact", "addcontact", "savecontact", "createcontact")) return addContact(ctx, action);
        if (is(name, "readsms", "readmessages", "readtext", "readmessage")) return readSms(ctx, action);
        if (is(name, "settings", "opensettings")) return settings(ctx, action);
        if (is(name, "wifi", "wifitoggle", "turnwifi")) return wifi(ctx, action);
        if (is(name, "bluetooth", "bttoggle", "turnbluetooth")) return bluetooth(ctx, action);
        if (is(name, "volume", "setvolume", "sound", "louder", "quieter", "mute")) return volume(ctx, action);
        throw new Exception("I don't know how to do that on this phone.");
    }

    private static Result call(Context ctx, JSONObject action) throws Exception {
        String number = number(ctx, action);
        Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
            return new Result("calling " + number);
        } catch (SecurityException e) {
            Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
            dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            start(ctx, dial, "I could not start the call.");
            return new Result("dialer opened with " + number);
        } catch (ActivityNotFoundException e) {
            throw new Exception("This phone has no dialer app.");
        }
    }

    private static Result sms(Context ctx, JSONObject action) throws Exception {
        String number = number(ctx, action);
        String message = optString(action, "message", "body", "text", "content");
        if (message.length() == 0) throw new Exception("The message text was empty.");
        try {
            SmsManager sms = SmsManager.getDefault();
            if (message.length() > 160) {
                sms.sendMultipartTextMessage(number, null, sms.divideMessage(message), null, null);
            } else {
                sms.sendTextMessage(number, null, message, null, null);
            }
            return new Result("text sent to " + number);
        } catch (Throwable t) {
            Intent compose = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
            compose.putExtra("sms_body", message);
            compose.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            start(ctx, compose, "I could not send the message.");
            return new Result("message opened for " + number);
        }
    }

    private static Result email(Context ctx, JSONObject action) throws Exception {
        String address = optString(action, "to", "email", "address");
        if (address.length() == 0) {
            String contact = optString(action, "contact", "name", "recipient");
            if (contact.length() > 0) address = lookupEmail(ctx, contact);
        }
        if (address.length() == 0 || address.indexOf('@') < 0) {
            throw new Exception("I could not find an email address for that contact.");
        }
        String subject = optString(action, "subject", "title");
        String message = optString(action, "message", "body", "text", "content");
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address)));
        if (subject.length() > 0) intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        if (message.length() > 0) intent.putExtra(Intent.EXTRA_TEXT, message);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(ctx, intent, "This phone has no email app.");
        return new Result("email draft opened for " + address);
    }

    private static Result alarm(Context ctx, JSONObject action) throws Exception {
        int[] time = clock(action);
        if (time == null) throw new Exception("I need a valid time for the alarm.");
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
        intent.putExtra(AlarmClock.EXTRA_HOUR, time[0]);
        intent.putExtra(AlarmClock.EXTRA_MINUTES, time[1]);
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        String label = optString(action, "label", "message", "title");
        if (label.length() > 0) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        int[] days = days(action);
        if (days != null && days.length > 0) intent.putExtra(AlarmClock.EXTRA_DAYS, days);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(ctx, intent, "I could not set the alarm on this phone.");
        String when = String.format(Locale.US, "%02d:%02d", time[0], time[1]);
        return new Result("alarm set for " + when + (days == null ? "" : " (repeating)"));
    }

    private static Result timer(Context ctx, JSONObject action) throws Exception {
        int seconds = seconds(action);
        if (seconds <= 0) throw new Exception("I need a length for the timer.");
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER);
        intent.putExtra(AlarmClock.EXTRA_LENGTH, seconds);
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        String label = optString(action, "label", "message", "title");
        if (label.length() > 0) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(ctx, intent, "I could not set the timer on this phone.");
        return new Result("timer set for " + durationText(seconds));
    }

    private static Result event(Context ctx, JSONObject action) throws Exception {
        String title = optString(action, "title", "subject", "event", "name");
        if (title.length() == 0) title = "Event";
        long[] range = eventRange(action);
        long begin = range[0];
        long finish = range[1];
        boolean allDay = range[2] == 1;
        String location = optString(action, "location", "place", "where");
        long calendarId = writableCalendar(ctx);
        if (calendarId > 0) {
            ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
            values.put(CalendarContract.Events.TITLE, title);
            values.put(CalendarContract.Events.DTSTART, begin);
            values.put(CalendarContract.Events.DTEND, finish);
            values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
            if (allDay) values.put(CalendarContract.Events.ALL_DAY, 1);
            if (location.length() > 0) values.put(CalendarContract.Events.EVENT_LOCATION, location);
            try {
                Uri uri = ctx.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
                if (uri != null) return new Result("calendar event added: " + title);
            } catch (Throwable ignored) {
            }
        }
        Intent intent = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI);
        intent.putExtra(CalendarContract.Events.TITLE, title);
        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin);
        intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, finish);
        if (allDay) intent.putExtra(CalendarContract.Events.ALL_DAY, 1);
        if (location.length() > 0) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(ctx, intent, "I could not add the calendar event.");
        return new Result("calendar editor opened for " + title);
    }

    private static Result openApp(Context ctx, JSONObject action) throws Exception {
        String wanted = optString(action, "app", "application", "name", "package");
        if (wanted.length() == 0) throw new Exception("Which app should I open?");
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            apps = null;
        }
        ApplicationInfo best = null;
        int bestScore = 0;
        String bestLabel = null;
        if (apps != null) {
            String lower = wanted.toLowerCase(Locale.US);
            for (ApplicationInfo info : apps) {
                if (info == null) continue;
                String label;
                try {
                    CharSequence cs = pm.getApplicationLabel(info);
                    label = cs == null ? "" : cs.toString();
                } catch (Throwable t) {
                    label = "";
                }
                String labelLower = label.toLowerCase(Locale.US);
                int score = 0;
                if (info.packageName.equalsIgnoreCase(wanted) || labelLower.equals(lower)) {
                    score = 3;
                } else if (labelLower.startsWith(lower)) {
                    score = 2;
                } else if (labelLower.contains(lower)) {
                    score = 1;
                }
                if (score > bestScore) {
                    best = info;
                    bestScore = score;
                    bestLabel = label;
                }
            }
        }
        if (best == null) throw new Exception("I could not find an app called " + wanted + ".");
        Intent launch = pm.getLaunchIntentForPackage(best.packageName);
        if (launch == null) throw new Exception("I could not find an app called " + wanted + ".");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(launch);
        return new Result("opened " + (bestLabel == null || bestLabel.length() == 0
                ? best.packageName : bestLabel));
    }

    private static Result search(Context ctx, JSONObject action) throws Exception {
        String query = optString(action, "query", "text", "search", "q", "what");
        if (query.length() == 0) throw new Exception("What should I search for?");
        Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
        intent.putExtra(SearchManager.QUERY, query);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Intent view = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            start(ctx, view, "I could not open a browser.");
        }
        return new Result("web search for " + query);
    }

    private static Result navigate(Context ctx, JSONObject action) throws Exception {
        String destination = optString(action, "destination", "address", "location", "query", "to");
        if (destination.length() == 0) throw new Exception("Where should I navigate to?");
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=" + Uri.encode(destination)));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(ctx, intent, "This phone has no maps app.");
        return new Result("navigating to " + destination);
    }

    private static Result playMusic(Context ctx, JSONObject action) throws Exception {
        String query = optString(action, "query", "song", "artist", "playlist", "music", "title", "name");
        if (query.length() == 0) {
            key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY);
            return new Result("music play");
        }
        Intent intent = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        intent.putExtra(SearchManager.QUERY, query);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY);
        }
        return new Result("music search for " + query);
    }

    private static Result media(Context ctx, JSONObject action) throws Exception {
        String command = optString(action, "command", "state", "action", "media");
        if (command.length() == 0) command = action.optString("name", "");
        command = normalize(command);
        int code;
        String label;
        if (is(command, "play", "resume", "start")) {
            code = KeyEvent.KEYCODE_MEDIA_PLAY;
            label = "music playing";
        } else if (is(command, "pause", "hold")) {
            code = KeyEvent.KEYCODE_MEDIA_PAUSE;
            label = "music paused";
        } else if (is(command, "stop")) {
            code = KeyEvent.KEYCODE_MEDIA_STOP;
            label = "music stopped";
        } else if (is(command, "next", "skip", "forward")) {
            code = KeyEvent.KEYCODE_MEDIA_NEXT;
            label = "next track";
        } else if (is(command, "previous", "back")) {
            code = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            label = "previous track";
        } else {
            throw new Exception("I did not understand that playback command.");
        }
        key(ctx, code);
        return new Result(label);
    }

    private static void key(Context ctx, int code) {
        Intent down = new Intent(Intent.ACTION_MEDIA_BUTTON);
        down.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, code));
        ctx.sendBroadcast(down);
        Intent up = new Intent(Intent.ACTION_MEDIA_BUTTON);
        up.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, code));
        ctx.sendBroadcast(up);
    }

    private static Result photo(Context ctx, boolean video) throws Exception {
        Intent intent = new Intent(video
                ? MediaStore.INTENT_ACTION_VIDEO_CAMERA
                : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(ctx, intent, "This phone has no camera app.");
        return new Result(video ? "video camera opened" : "camera opened");
    }

    private static Result addContact(Context ctx, JSONObject action) throws Exception {
        String name = optString(action, "name", "contact", "person");
        String number = optString(action, "number", "phone", "mobile");
        String email = optString(action, "email", "address");
        String company = optString(action, "company", "organisation", "organization");
        if (name.length() == 0 && number.length() == 0) {
            throw new Exception("I need a name or phone number for the contact.");
        }
        Intent intent = new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI);
        if (name.length() > 0) intent.putExtra(ContactsContract.Intents.Insert.NAME, name);
        if (number.length() > 0) intent.putExtra(ContactsContract.Intents.Insert.PHONE, number);
        if (email.length() > 0) intent.putExtra(ContactsContract.Intents.Insert.EMAIL, email);
        if (company.length() > 0) intent.putExtra(ContactsContract.Intents.Insert.COMPANY, company);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(ctx, intent, "This phone has no contacts app.");
        return new Result("contact editor opened" + (name.length() > 0 ? " for " + name : ""));
    }

    private static Result readSms(Context ctx, JSONObject action) throws Exception {
        String contact = optString(action, "contact", "name", "from");
        String number = contact.length() > 0 ? lookup(ctx, contact) : "";
        if (contact.length() > 0 && number.length() == 0) {
            throw new Exception("I could not find a message from " + contact + ".");
        }
        boolean filter = number.length() > 0;
        String body = null;
        String address = null;
        Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{"address", "body"},
                    filter ? "address=?" : null,
                    filter ? new String[]{number} : null,
                    "date DESC");
            if (cursor != null && cursor.moveToFirst()) {
                address = cursor.getString(0);
                body = cursor.getString(1);
            }
        } catch (SecurityException e) {
            throw new Exception("I need permission to read messages on this phone.");
        } catch (Throwable t) {
            throw new Exception("I could not read the messages on this phone.");
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (body == null) throw new Exception("There are no messages to read.");
        if (body.length() > 500) body = body.substring(0, 500) + "...";
        String say = "Your latest message from " + (address == null ? "someone" : address)
                + " says: " + body;
        return new Result("read the latest message", say);
    }

    private static Result settings(Context ctx, JSONObject action) throws Exception {
        String screen = normalize(optString(action, "screen", "panel", "page"));
        String name = "settings";
        String actionName = Settings.ACTION_SETTINGS;
        if (is(screen, "wifi", "wireless")) {
            actionName = Settings.ACTION_WIFI_SETTINGS;
            name = "Wi-Fi settings";
        } else if (is(screen, "bluetooth", "bt")) {
            actionName = Settings.ACTION_BLUETOOTH_SETTINGS;
            name = "Bluetooth settings";
        } else if (is(screen, "display", "screen", "brightness")) {
            actionName = Settings.ACTION_DISPLAY_SETTINGS;
            name = "display settings";
        } else if (is(screen, "sound", "audio", "volume")) {
            actionName = Settings.ACTION_SOUND_SETTINGS;
            name = "sound settings";
        } else if (is(screen, "battery", "power")) {
            actionName = "android.settings.BATTERY_SAVER_SETTINGS";
            name = "battery settings";
        } else if (is(screen, "apps", "applications", "app")) {
            actionName = Settings.ACTION_APPLICATION_SETTINGS;
            name = "app settings";
        } else if (is(screen, "date", "time", "clock")) {
            actionName = Settings.ACTION_DATE_SETTINGS;
            name = "date and time settings";
        } else if (is(screen, "security", "lock", "lockscreen")) {
            actionName = Settings.ACTION_SECURITY_SETTINGS;
            name = "security settings";
        } else if (is(screen, "location", "gps")) {
            actionName = Settings.ACTION_LOCATION_SOURCE_SETTINGS;
            name = "location settings";
        } else if (is(screen, "storage", "memory")) {
            actionName = Settings.ACTION_INTERNAL_STORAGE_SETTINGS;
            name = "storage settings";
        } else if (is(screen, "data", "mobile", "network", "cellular")) {
            actionName = Settings.ACTION_DATA_ROAMING_SETTINGS;
            name = "mobile network settings";
        } else if (is(screen, "airplane", "flight")) {
            actionName = Settings.ACTION_AIRPLANE_MODE_SETTINGS;
            name = "airplane mode settings";
        }
        Intent intent = new Intent(actionName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Intent all = new Intent(Settings.ACTION_SETTINGS);
            all.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            start(ctx, all, "I could not open settings.");
        }
        return new Result("opened " + name);
    }

    private static Result wifi(Context ctx, JSONObject action) throws Exception {
        boolean on = state(action);
        WifiManager wifi = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) throw new Exception("This phone has no Wi-Fi.");
        try {
            wifi.setWifiEnabled(on);
        } catch (SecurityException e) {
            throw new Exception("I am not allowed to change Wi-Fi on this phone.");
        }
        return new Result("wifi " + (on ? "on" : "off"));
    }

    private static Result bluetooth(Context ctx, JSONObject action) throws Exception {
        boolean on = state(action);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new Exception("This phone has no Bluetooth.");
        try {
            if (on) {
                adapter.enable();
            } else {
                adapter.disable();
            }
        } catch (SecurityException e) {
            throw new Exception("I am not allowed to change Bluetooth on this phone.");
        }
        return new Result("bluetooth " + (on ? "on" : "off"));
    }

    private static Result volume(Context ctx, JSONObject action) throws Exception {
        String level = normalize(optString(action, "level", "direction", "state", "command"));
        AudioManager audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) throw new Exception("I could not reach the volume controls.");
        int stream = AudioManager.STREAM_MUSIC;
        if (is(level, "mute", "silent", "off")) {
            audio.setStreamVolume(stream, 0, 0);
            return new Result("muted");
        }
        if (is(level, "max", "maximum", "full", "loudest")) {
            audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream), 0);
            return new Result("volume at maximum");
        }
        if (is(level, "down", "lower", "quieter", "softer", "decrease")) {
            audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0);
            return new Result("volume down");
        }
        audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0);
        return new Result("volume up");
    }

    private static void start(Context ctx, Intent intent, String reason) throws Exception {
        try {
            ctx.startActivity(intent);
        } catch (SecurityException e) {
            throw new Exception(reason);
        } catch (ActivityNotFoundException e) {
            throw new Exception(reason);
        } catch (Throwable t) {
            throw new Exception(reason);
        }
    }

    private static String number(Context ctx, JSONObject action) throws Exception {
        String raw = optString(action, "number", "phone", "tel", "mobile");
        if (raw.length() == 0) raw = optString(action, "contact", "name", "recipient", "to");
        if (raw.length() == 0) throw new Exception("I could not find a phone number for that contact.");
        if (looksLikeNumber(raw)) return cleanNumber(raw);
        String found = lookup(ctx, raw);
        if (found.length() == 0) {
            throw new Exception("I could not find a phone number for " + raw + ".");
        }
        return cleanNumber(found);
    }

    private static boolean looksLikeNumber(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 3) return false;
        return value.replaceAll("[0-9+\\s()\\-.]", "").length() == 0;
    }

    private static String cleanNumber(String value) {
        return value.replaceAll("[\\s()\\-.]", "").trim();
    }

    private static String lookup(Context ctx, String contact) {
        String[] projection = {ContactsContract.CommonDataKinds.Phone.NUMBER};
        String[] columns = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ?",
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?"
        };
        String[] values = {contact, contact + "%", "%" + contact + "%"};
        for (int i = 0; i < columns.length; i++) {
            String found = queryPhone(ctx, projection, columns[i], values[i]);
            if (found.length() > 0) return found;
        }
        return "";
    }

    private static String queryPhone(Context ctx, String[] projection, String selection, String arg) {
        Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection, selection, new String[]{arg}, null);
            if (cursor != null && cursor.moveToFirst()) {
                String number = cursor.getString(0);
                if (number != null && number.trim().length() > 0) return number.trim();
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

    private static String lookupEmail(Context ctx, String contact) {
        String[] columns = {
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME + " = ?",
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME + " LIKE ?",
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME + " LIKE ?"
        };
        String[] values = {contact, contact + "%", "%" + contact + "%"};
        for (int i = 0; i < columns.length; i++) {
            Cursor cursor = null;
            try {
                cursor = ctx.getContentResolver().query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        new String[]{ContactsContract.CommonDataKinds.Email.ADDRESS},
                        columns[i], new String[]{values[i]}, null);
                if (cursor != null && cursor.moveToFirst()) {
                    String address = cursor.getString(0);
                    if (address != null && address.trim().length() > 0) return address.trim();
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
        }
        return "";
    }

    private static long writableCalendar(Context ctx) {
        String[] projection = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        };
        Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(
                    CalendarContract.Calendars.CONTENT_URI, projection,
                    CalendarContract.Calendars.VISIBLE + "=1", null, null);
            long fallback = 0;
            while (cursor != null && cursor.moveToNext()) {
                long id = cursor.getLong(0);
                int access = cursor.getInt(1);
                if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) return id;
                if (fallback == 0) fallback = id;
            }
            return fallback;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** begin, end, allDay (1/0). */
    private static long[] eventRange(JSONObject action) throws Exception {
        String date = optString(action, "date", "day");
        String start = optString(action, "startTime", "start", "time", "at");
        String end = optString(action, "endTime", "end");
        int minutes = optInt(action, -1, "durationMinutes", "minutes", "duration");
        Calendar cal = Calendar.getInstance();
        if (date.length() > 0) {
            Date day = parseDate(date);
            if (day == null) throw new Exception("I did not understand that date.");
            cal.setTime(day);
        }
        int[] begin = start.length() > 0 ? parseClock(start) : null;
        if (begin == null) {
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long midnight = cal.getTimeInMillis();
            return new long[]{midnight, midnight + 24L * 3600L * 1000L, 1};
        }
        cal.set(Calendar.HOUR_OF_DAY, begin[0]);
        cal.set(Calendar.MINUTE, begin[1]);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startMillis = cal.getTimeInMillis();
        if (end.length() > 0) {
            int[] finish = parseClock(end);
            if (finish != null) {
                Calendar endCal = Calendar.getInstance();
                endCal.setTimeInMillis(startMillis);
                endCal.set(Calendar.HOUR_OF_DAY, finish[0]);
                endCal.set(Calendar.MINUTE, finish[1]);
                long endMillis = endCal.getTimeInMillis();
                if (endMillis <= startMillis) endMillis += 24L * 3600L * 1000L;
                return new long[]{startMillis, endMillis, 0};
            }
        }
        long span = (minutes > 0 ? minutes : 60) * 60000L;
        return new long[]{startMillis, startMillis + span, 0};
    }

    private static Date parseDate(String value) {
        String text = value.trim();
        String lower = text.toLowerCase(Locale.US);
        Calendar cal = Calendar.getInstance();
        if (lower.equals("today")) {
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTime();
        }
        if (lower.equals("tomorrow")) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTime();
        }
        int t = text.indexOf('T');
        if (t > 0) text = text.substring(0, t);
        String[] formats = {"yyyy-MM-dd", "yyyy/MM/dd", "dd.MM.yyyy", "MM/dd/yyyy"};
        for (String format : formats) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.US);
                sdf.setLenient(false);
                return sdf.parse(text);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int[] clock(JSONObject action) {
        int hour = optInt(action, -1, "hour", "hours");
        int minute = optInt(action, -1, "minute", "minutes");
        if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
            return new int[]{hour, minute};
        }
        if (hour >= 0 && hour <= 23 && minute < 0) return new int[]{hour, 0};
        String time = optString(action, "time", "at", "when", "clock");
        return time.length() == 0 ? null : parseClock(time);
    }

    private static int[] parseClock(String value) {
        String text = value.trim().toLowerCase(Locale.US)
                .replace("a.m.", "am").replace("p.m.", "pm");
        boolean pm = text.contains("pm");
        boolean am = text.contains("am");
        text = text.replace("am", "").replace("pm", "").trim();
        if (text.indexOf(':') < 0 && text.indexOf('.') >= 0) {
            text = text.replace('.', ':');
        }
        Matcher m = CLOCK.matcher(text);
        if (!m.matches()) return null;
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (pm && hour < 12) hour += 12;
        if (am && hour == 12) hour = 0;
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
        return new int[]{hour, minute};
    }

    private static int[] days(JSONObject action) {
        JSONArray array = action.optJSONArray("days");
        String repeat = normalize(optString(action, "repeat", "repeats", "frequency"));
        int[] weekdays = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY};
        int[] weekend = {Calendar.SATURDAY, Calendar.SUNDAY};
        int[] everyDay = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
        if (array == null || array.length() == 0) {
            if (repeat.equals("weekdays") || repeat.equals("everyweekday")
                    || repeat.equals("workdays")) return weekdays;
            if (repeat.equals("weekends")) return weekend;
            if (repeat.equals("daily") || repeat.equals("everyday")) return everyDay;
            return null;
        }
        int[] result = new int[array.length()];
        int n = 0;
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            int day = -1;
            if (item instanceof Number) {
                int raw = ((Number) item).intValue();
                day = raw >= 1 && raw <= 7 ? raw + 1 : -1;
            } else if (item != null) {
                day = dayOf(String.valueOf(item));
            }
            if (day >= 0) result[n++] = day;
        }
        if (n == 0) return null;
        int[] trimmed = new int[n];
        System.arraycopy(result, 0, trimmed, 0, n);
        return trimmed;
    }

    private static int dayOf(String value) {
        String name = normalize(value);
        if (name.equals("monday") || name.equals("mon")) return Calendar.MONDAY;
        if (name.equals("tuesday") || name.equals("tue") || name.equals("tues")) return Calendar.TUESDAY;
        if (name.equals("wednesday") || name.equals("wed")) return Calendar.WEDNESDAY;
        if (name.equals("thursday") || name.equals("thu") || name.equals("thurs")) return Calendar.THURSDAY;
        if (name.equals("friday") || name.equals("fri")) return Calendar.FRIDAY;
        if (name.equals("saturday") || name.equals("sat")) return Calendar.SATURDAY;
        if (name.equals("sunday") || name.equals("sun")) return Calendar.SUNDAY;
        return -1;
    }

    private static int seconds(JSONObject action) {
        int seconds = optInt(action, -1, "seconds", "secs");
        if (seconds > 0) return seconds;
        int minutes = optInt(action, -1, "minutes", "mins");
        if (minutes > 0) return minutes * 60;
        int hours = optInt(action, -1, "hours", "hrs");
        if (hours > 0) return hours * 3600;
        String text = optString(action, "duration", "length", "for");
        if (text.length() > 0) {
            int parsed = parseDuration(text);
            if (parsed > 0) return parsed;
        }
        return -1;
    }

    private static int parseDuration(String value) {
        String text = value.toLowerCase(Locale.US).replace(",", ".");
        Matcher m = DURATION.matcher(text);
        double total = 0;
        boolean found = false;
        while (m.find()) {
            double amount = Double.parseDouble(m.group(1));
            String unit = m.group(2);
            if (unit.startsWith("h")) total += amount * 3600;
            else if (unit.startsWith("m")) total += amount * 60;
            else total += amount;
            found = true;
        }
        if (!found) {
            try {
                return Integer.parseInt(text.replaceAll("[^0-9]", ""));
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return (int) Math.round(total);
    }

    private static String durationText(int seconds) {
        if (seconds % 3600 == 0) {
            int hours = seconds / 3600;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        if (seconds % 60 == 0) {
            int minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    private static boolean state(JSONObject action) throws Exception {
        String value = normalize(optString(action, "state", "status", "value", "on"));
        if (value.equals("on") || value.equals("enable") || value.equals("true") || value.equals("yes")) {
            return true;
        }
        if (value.equals("off") || value.equals("disable") || value.equals("false") || value.equals("no")) {
            return false;
        }
        throw new Exception("Should I turn it on or off?");
    }

    private static String optString(JSONObject o, String... keys) {
        for (String key : keys) {
            try {
                String value = o.optString(key, "");
                if (value != null) {
                    value = value.trim();
                    if (value.length() > 0 && !"null".equalsIgnoreCase(value)
                            && !"undefined".equalsIgnoreCase(value)) {
                        return value;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static int optInt(JSONObject o, int fallback, String... keys) {
        for (String key : keys) {
            if (!o.has(key)) continue;
            try {
                int value = o.optInt(key, Integer.MIN_VALUE);
                if (value != Integer.MIN_VALUE) return value;
            } catch (Throwable ignored) {
            }
            try {
                String text = o.optString(key, "");
                Matcher m = Pattern.compile("-?\\d+").matcher(text);
                if (m.find()) return Integer.parseInt(m.group());
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private static boolean is(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) return true;
        }
        return false;
    }
}
