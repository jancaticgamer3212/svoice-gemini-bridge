package com.svoice.gemini;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

final class GroqClient {
    private static final String BASE = "https://api.groq.com/openai/v1";
    private static final int BUSY_ATTEMPTS = 2;

    // Tried in order after the configured model, because Groq retires models often.
    private static final String[] CHAT_MODELS = {
            Settings.DEFAULT_GROQ_MODEL, "qwen/qwen3.8-27b", "openai/gpt-oss-20b",
            "llama-3.3-70b-versatile", "llama-3.1-8b-instant"
    };
    private static final String[] TRANSCRIBE_MODELS = {
            "whisper-large-v3-turbo", "whisper-large-v3"
    };

    static final class Failure extends Exception {
        final int code;
        final boolean retryable;

        Failure(int code, String message) {
            this(code, true, message);
        }

        Failure(int code, boolean retryable, String message) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }
    }

    private GroqClient() {}

    static GeminiClient.Answer askAudio(Context ctx, Settings cfg, byte[] wav) throws Exception {
        String transcript = transcribe(ctx, cfg, wav);
        if (transcript.length() == 0) {
            throw new Failure(0, "Groq did not hear any speech.");
        }
        GeminiClient.Answer answer = chatAnswer(ctx, cfg, transcript);
        return new GeminiClient.Answer(transcript, answer.answer, answer.action);
    }

    static GeminiClient.Answer askText(Context ctx, Settings cfg, String text) throws Exception {
        return chatAnswer(ctx, cfg, text);
    }

    private static GeminiClient.Answer chatAnswer(Context ctx, Settings cfg, String text)
            throws Exception {
        return GeminiClient.parse(chat(ctx, cfg,
                Settings.TEXT_PROMPT + Settings.timeContext() + "\n\nUser: " + text));
    }

    static String testKey(Context ctx, Settings cfg) throws Exception {
        return chat(ctx, cfg, "Reply with the single word OK.");
    }

    private static String transcribe(Context ctx, Settings cfg, byte[] wav) throws Exception {
        Failure last = null;
        for (String model : TRANSCRIBE_MODELS) {
            try {
                return transcribeWith(cfg, model, wav);
            } catch (Failure e) {
                last = e;
                if (!isMissingModel(e)) throw e;
            }
        }
        throw last;
    }

    private static String transcribeWith(Settings cfg, String model, byte[] wav) throws Exception {
        String boundary = "----svoice" + System.currentTimeMillis();
        ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 1024);
        writeField(out, boundary, "model", model);
        writeField(out, boundary, "response_format", "json");
        writeFile(out, boundary, "file", "audio.wav", "audio/wav", wav);
        writeEnd(out, boundary);

        String raw = send(cfg, BASE + "/audio/transcriptions",
                "multipart/form-data; boundary=" + boundary, out.toByteArray());
        return new JSONObject(raw).optString("text", "").trim();
    }

    private static String chat(Context ctx, Settings cfg, String userText) throws Exception {
        Failure last = null;
        for (String model : chatModels(cfg)) {
            try {
                try {
                    return chatWith(cfg, model, userText, false);
                } catch (Failure e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                    if (e.code == 400 && !isMissingModel(e)
                            && (msg.contains("max_tokens") || msg.contains("temperature")
                            || msg.contains("reasoning_effort") || msg.contains("unsupported"))) {
                        return chatWith(cfg, model, userText, true);
                    }
                    throw e;
                }
            } catch (Failure e) {
                last = e;
                if (!isMissingModel(e)) throw e;
            }
        }
        throw last;
    }

    private static String[] chatModels(Settings cfg) {
        String configured = cfg.groqModel == null ? "" : cfg.groqModel.trim();
        if (configured.length() == 0) configured = Settings.DEFAULT_GROQ_MODEL;
        String[] chain = new String[CHAT_MODELS.length + 1];
        chain[0] = configured;
        int n = 1;
        for (String model : CHAT_MODELS) {
            if (!model.equals(configured)) chain[n++] = model;
        }
        String[] trimmed = new String[n];
        System.arraycopy(chain, 0, trimmed, 0, n);
        return trimmed;
    }

    private static String chatWith(Settings cfg, String model, String userText, boolean minimal)
            throws Exception {
        if (!model.matches("[A-Za-z0-9._:/-]+")) {
            throw new Failure(0, false, "Invalid Groq model name: " + model);
        }

        JSONArray messages = new JSONArray();
        if (cfg.systemPrompt != null && cfg.systemPrompt.length() > 0) {
            messages.put(new JSONObject().put("role", "system").put("content", cfg.systemPrompt));
        }
        messages.put(new JSONObject().put("role", "user").put("content", userText));

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        if (!minimal) {
            body.put("temperature", 0.4);
            // gpt-oss models spend tokens on hidden reasoning before writing the answer.
            body.put("max_tokens", 1024);
            if (model.startsWith("openai/gpt-oss")) {
                body.put("reasoning_effort", "low");
            }
        }

        String raw = send(cfg, BASE + "/chat/completions", "application/json; charset=utf-8",
                body.toString().getBytes(Charsets.UTF8));
        JSONArray choices = new JSONObject(raw).optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Failure(0, "Groq returned no answer.");
        }
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        String text = message == null ? "" : message.optString("content", "").trim();
        if (text.length() == 0) throw new Failure(0, "Groq returned an empty answer.");
        return text;
    }

    private static String send(Settings cfg, String url, String contentType, byte[] payload)
            throws Exception {
        String key = cfg.groqKey == null ? "" : cfg.groqKey.trim();
        if (key.length() == 0) {
            throw new Failure(0, false, "No Groq API key configured.");
        }
        Failure last = null;
        for (int attempt = 0; attempt < BUSY_ATTEMPTS; attempt++) {
            try {
                return post(key, url, contentType, payload);
            } catch (Failure e) {
                last = e;
                if (!isBusy(e.code) || !e.retryable) throw e;
                if (attempt < BUSY_ATTEMPTS - 1) pause(2000L * (attempt + 1));
            }
        }
        throw last;
    }

    private static String post(String key, String url, String contentType, byte[] payload)
            throws Exception {
        HttpsURLConnection conn = (HttpsURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setSSLSocketFactory(Http.factory());
            conn.connect();

            OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
            } finally {
                try { out.close(); } catch (Throwable ignored) {}
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            if (code < 200 || code >= 300) {
                throw new Failure(code, describeError(code, text));
            }
            return text;
        } finally {
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static boolean isMissingModel(Failure e) {
        if (e.code != 400 && e.code != 404) return false;
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("model") || msg.contains("decommissioned") || msg.contains("not found");
    }

    private static void writeField(ByteArrayOutputStream out, String boundary, String name, String value)
            throws Exception {
        String part = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        out.write(part.getBytes(Charsets.UTF8));
    }

    private static void writeFile(ByteArrayOutputStream out, String boundary, String name, String filename,
                                  String type, byte[] data) throws Exception {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n";
        out.write(head.getBytes(Charsets.UTF8));
        out.write(data);
        out.write("\r\n".getBytes(Charsets.UTF8));
    }

    private static void writeEnd(ByteArrayOutputStream out, String boundary) throws Exception {
        out.write(("--" + boundary + "--\r\n").getBytes(Charsets.UTF8));
    }

    private static boolean isBusy(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describeError(int code, String body) {
        String msg = "";
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                msg = error.optString("message", "");
                String type = error.optString("type", "");
                if (type.length() > 0) msg = msg + " [" + type + "]";
            }
        } catch (Throwable ignored) {
        }
        if (msg.length() == 0) msg = body == null ? "" : body.trim();
        if (msg.length() > 220) msg = msg.substring(0, 220) + "...";
        if (code == 401 || code == 403) {
            return "Groq rejected the API key. Check it in S Voice Gemini Bridge.";
        }
        if (code == 429) {
            return "Groq rate limit reached. Wait a minute, or Gemini will be used alone.";
        }
        if (isBusy(code)) {
            return "Groq is overloaded right now. Tap the mic and try again in a moment.";
        }
        return "Groq error " + code + ": " + msg;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), Charsets.UTF8);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
