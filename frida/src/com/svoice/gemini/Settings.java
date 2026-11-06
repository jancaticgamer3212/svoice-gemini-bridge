package com.svoice.gemini;

import android.os.Environment;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

final class Settings {
    static final String MODULE_PACKAGE = "com.svoice.gemini";
    static final String PREFS_NAME = "settings";
    static final String EXTERNAL_FILE = ".svoice_gemini.json";

    static final String DEFAULT_MODEL = "gemini-3.6-flash";
    static final String DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b";

    /** Prompt shipped by 2.6 and earlier; replaced on load so old installs get the new one. */
    static final String LEGACY_SYSTEM_PROMPT =
            "You are the assistant behind Samsung S Voice. Reply in the same language the user used. "
            + "Be concise and conversational: one to three short sentences. Never use markdown, lists, "
            + "emoji or stage directions, because your reply is spoken aloud. Never claim to have "
            + "performed an action on the phone that you cannot perform.";

    static final String DEFAULT_SYSTEM_PROMPT =
            "You are a helpful voice assistant on the user's Android phone. Reply in the same language "
            + "the user used. Be concise and conversational: one to three short sentences. Never use "
            + "markdown, lists, emoji or stage directions, because your reply is spoken aloud. You can "
            + "call, send text messages and set alarms or timers on the phone when the user asks; only "
            + "say an action happened if the action object below was returned.";

    static final String ACTION_PROMPT =
            " If the user asks you to call someone, send a text message, set an alarm or set a timer, "
            + "include an \"action\" object. Use one of these shapes and fill in what the user said:\n"
            + "{\"name\":\"call\",\"number\":\"+15551234567\"} or {\"contact\":\"Mom\"}\n"
            + "{\"name\":\"sms\",\"contact\":\"Mom\",\"message\":\"On my way\"}\n"
            + "{\"name\":\"alarm\",\"hour\":7,\"minute\":30,\"label\":\"Wake up\"} (24-hour clock)\n"
            + "{\"name\":\"timer\",\"seconds\":300,\"label\":\"Pasta\"}\n"
            + "If no action is needed, omit the action field or set it to null. Resolve currencies, "
            + "units and times to the plain values shown above. Never invent a phone number.";

    static final String AUDIO_PROMPT =
            "First transcribe the user's speech verbatim in the language spoken. Then answer it as the "
            + "assistant described in the system instruction. Return only a JSON object with the string "
            + "fields {\"transcript\": \"...\", \"answer\": \"...\"} and, when an action is required, "
            + "the \"action\" object described below. The answer must be plain text."
            + ACTION_PROMPT;

    static final String TEXT_PROMPT =
            "Answer the user's message as the assistant described in the system instruction. Return "
            + "only a JSON object with the string field {\"answer\": \"...\"} and, when an action is "
            + "required, the \"action\" object described below. The answer must be plain text."
            + ACTION_PROMPT;

    boolean enabled = true;
    String apiKey = "";
    String model = DEFAULT_MODEL;
    String groqKey = "";
    String groqModel = DEFAULT_GROQ_MODEL;
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
        s.normalize();
        return s;
    }

    private static void applyJson(Settings s, JSONObject o) {
        s.enabled = o.optBoolean("enabled", true);
        s.apiKey = o.optString("apiKey", "");
        s.model = o.optString("model", DEFAULT_MODEL);
        s.groqKey = o.optString("groqKey", "");
        s.groqModel = o.optString("groqModel", DEFAULT_GROQ_MODEL);
        s.systemPrompt = o.optString("systemPrompt", DEFAULT_SYSTEM_PROMPT);
        if (LEGACY_SYSTEM_PROMPT.equals(s.systemPrompt.trim())) {
            s.systemPrompt = DEFAULT_SYSTEM_PROMPT;
        }
        s.speak = o.optBoolean("speak", true);
        s.normalize();
    }

    private void normalize() {
        if (apiKey == null) apiKey = "";
        apiKey = apiKey.trim();
        if (model == null || model.trim().length() == 0) model = DEFAULT_MODEL;
        model = model.trim();
        if (groqKey == null) groqKey = "";
        groqKey = groqKey.trim();
        if (groqModel == null || groqModel.trim().length() == 0) groqModel = DEFAULT_GROQ_MODEL;
        groqModel = groqModel.trim();
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
