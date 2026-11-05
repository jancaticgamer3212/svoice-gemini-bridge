package com.svoice.gemini;

import android.os.Environment;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XSharedPreferences;

final class Settings {
    static final String MODULE_PACKAGE = "com.svoice.gemini";
    static final String PREFS_NAME = "settings";
    static final String EXTERNAL_FILE = ".svoice_gemini.json";

    static final String DEFAULT_MODEL = "gemini-3.6-flash";

    static final String DEFAULT_SYSTEM_PROMPT =
            "You are the assistant behind Samsung S Voice. Reply in the same language the user used. "
            + "Be concise and conversational: one to three short sentences. Never use markdown, lists, "
            + "emoji or stage directions, because your reply is spoken aloud. Never claim to have "
            + "performed an action on the phone that you cannot perform.";

    static final String AUDIO_PROMPT =
            "First transcribe the user's speech verbatim in the language spoken. Then answer it as the "
            + "assistant described in the system instruction. Return only a JSON object with two string "
            + "fields: {\"transcript\": \"...\", \"answer\": \"...\"}. The answer must be plain text.";

    boolean enabled = true;
    String apiKey = "";
    String model = DEFAULT_MODEL;
    String systemPrompt = DEFAULT_SYSTEM_PROMPT;
    boolean speak = true;

    static Settings load() {
        Settings s = new Settings();
        try {
            File f = externalFile();
            if (f != null && f.isFile() && f.canRead()) {
                String json = readFile(f);
                if (json != null && json.length() > 0) {
                    applyJson(s, new JSONObject(json));
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            XSharedPreferences p = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
            p.reload();
            s.enabled = p.getBoolean("enabled", true);
            s.apiKey = p.getString("api_key", "");
            s.model = p.getString("model", DEFAULT_MODEL);
            s.systemPrompt = p.getString("system_prompt", DEFAULT_SYSTEM_PROMPT);
            s.speak = p.getBoolean("speak", true);
        } catch (Throwable ignored) {
        }
        s.normalize();
        return s;
    }

    private static void applyJson(Settings s, JSONObject o) {
        s.enabled = o.optBoolean("enabled", true);
        s.apiKey = o.optString("apiKey", "");
        s.model = o.optString("model", DEFAULT_MODEL);
        s.systemPrompt = o.optString("systemPrompt", DEFAULT_SYSTEM_PROMPT);
        s.speak = o.optBoolean("speak", true);
        s.normalize();
    }

    private void normalize() {
        if (apiKey == null) apiKey = "";
        apiKey = apiKey.trim();
        if (model == null || model.trim().length() == 0) model = DEFAULT_MODEL;
        model = model.trim();
        if (systemPrompt == null) systemPrompt = "";
        systemPrompt = systemPrompt.trim();
        if (systemPrompt.length() == 0) systemPrompt = DEFAULT_SYSTEM_PROMPT;
    }

    static File externalFile() {
        try {
            File dir = Environment.getExternalStorageDirectory();
            if (dir == null) return null;
            return new File(dir, EXTERNAL_FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
