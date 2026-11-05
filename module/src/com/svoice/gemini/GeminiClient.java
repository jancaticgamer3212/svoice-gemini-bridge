package com.svoice.gemini;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

final class GeminiClient {
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    static final class Answer {
        final String transcript;
        final String answer;

        Answer(String transcript, String answer) {
            this.transcript = transcript == null ? "" : transcript;
            this.answer = answer == null ? "" : answer;
        }
    }

    static final class ApiException extends Exception {
        final int code;

        ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private GeminiClient() {}

    static Answer askAudio(Context ctx, Settings cfg, byte[] wav) throws Exception {
        String raw = request(ctx, cfg, Settings.AUDIO_PROMPT, wav);
        return parseAnswer(raw);
    }

    static String askText(Context ctx, Settings cfg, String text) throws Exception {
        String raw = request(ctx, cfg, text, null);
        return cleanAnswer(raw);
    }

    static String testKey(Context ctx, Settings cfg) throws Exception {
        return request(ctx, cfg, "Reply with the single word OK.", null);
    }

    private static String request(Context ctx, Settings cfg, String userText, byte[] wav) throws Exception {
        boolean thinking = supportsThinkingConfig(cfg.model);
        try {
            return doRequest(ctx, cfg, userText, wav, thinking);
        } catch (ApiException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (thinking && e.code == 400 && msg.contains("thinking")) {
                return doRequest(ctx, cfg, userText, wav, false);
            }
            throw e;
        }
    }

    private static boolean supportsThinkingConfig(String model) {
        if (model == null) return true;
        String m = model.toLowerCase();
        return m.contains("2.5-flash");
    }

    private static String doRequest(Context ctx, Settings cfg, String userText, byte[] wav,
                                    boolean disableThinking) throws Exception {
        if (cfg.apiKey == null || cfg.apiKey.length() == 0) {
            throw new ApiException(0, "No API key configured. Open S Voice Gemini Bridge and paste your key.");
        }
        String model = cfg.model == null ? "" : cfg.model.trim();
        if (model.length() == 0) model = Settings.DEFAULT_MODEL;
        if (!model.matches("[A-Za-z0-9._-]+")) {
            throw new ApiException(0, "Invalid model name: " + model);
        }

        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject turn = new JSONObject();
        turn.put("role", "user");
        JSONArray parts = new JSONArray();
        if (wav != null && wav.length > 0) {
            JSONObject inline = new JSONObject();
            inline.put("mimeType", "audio/wav");
            inline.put("data", Base64.encodeToString(wav, Base64.NO_WRAP));
            parts.put(new JSONObject().put("inlineData", inline));
        }
        parts.put(new JSONObject().put("text", userText));
        turn.put("parts", parts);
        contents.put(turn);
        body.put("contents", contents);

        if (cfg.systemPrompt != null && cfg.systemPrompt.length() > 0) {
            JSONObject sys = new JSONObject();
            sys.put("parts", new JSONArray().put(new JSONObject().put("text", cfg.systemPrompt)));
            body.put("systemInstruction", sys);
        }

        JSONObject gen = new JSONObject();
        gen.put("temperature", 0.4);
        gen.put("maxOutputTokens", 2048);
        if (disableThinking) {
            gen.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
        }
        body.put("generationConfig", gen);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        URL url = new URL(ENDPOINT + model + ":generateContent");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("x-goog-api-key", cfg.apiKey.trim());
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
                throw new ApiException(code, describeError(code, text));
            }
            return extractModelText(text);
        } finally {
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String extractModelText(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            String blocked = root.optString("promptFeedback", "");
            throw new ApiException(0, blocked.length() > 0 ? "Request was blocked: " + blocked : "Gemini returned no answer.");
        }
        JSONObject candidate = candidates.getJSONObject(0);
        JSONObject content = candidate.optJSONObject("content");
        if (content == null) {
            String reason = candidate.optString("finishReason", "unknown");
            throw new ApiException(0, "Gemini returned no content (" + reason + ").");
        }
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) throw new ApiException(0, "Gemini returned no text.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p == null) continue;
            String t = p.optString("text", "");
            if (t.length() > 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
        }
        String text = sb.toString().trim();
        if (text.length() == 0) throw new ApiException(0, "Gemini returned an empty answer.");
        return text;
    }

    private static Answer parseAnswer(String raw) {
        String text = raw.trim();
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open >= 0 && close > open) {
            try {
                JSONObject o = new JSONObject(text.substring(open, close + 1));
                String transcript = o.optString("transcript", "").trim();
                String answer = o.optString("answer", "").trim();
                if (answer.length() > 0) {
                    return new Answer(transcript, cleanAnswer(answer));
                }
            } catch (Throwable ignored) {
            }
        }
        return new Answer("", cleanAnswer(text));
    }

    private static String cleanAnswer(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) text = text.substring(firstNewline + 1);
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
            text = text.trim();
        }
        return text;
    }

    private static String describeError(int code, String body) {
        String msg = "";
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                msg = error.optString("message", "");
                String status = error.optString("status", "");
                if (status.length() > 0) msg = msg + " [" + status + "]";
            }
        } catch (Throwable ignored) {
        }
        if (msg.length() == 0) msg = body == null ? "" : body.trim();
        if (msg.length() > 220) msg = msg.substring(0, 220) + "...";
        if (code == 400 && msg.contains("API key")) {
            msg = "Gemini rejected the API key. Check it in S Voice Gemini Bridge.";
        } else if (code == 401 || code == 403) {
            msg = "Gemini refused the request (" + code + "): " + msg;
        }
        return "Gemini error " + code + ": " + msg;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
