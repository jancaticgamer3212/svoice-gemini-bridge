package com.svoice.gemini;

import android.content.Context;
import android.util.Log;

/** Answers with Gemini, and falls back to Groq whenever Gemini cannot. */
final class Assistant {
    private static final String TAG = "SVoiceGemini";

    private Assistant() {}

    static boolean hasAnyKey(Settings cfg) {
        return has(cfg.apiKey) || has(cfg.groqKey);
    }

    static GeminiClient.Answer askAudio(Context ctx, Settings cfg, byte[] wav) throws Exception {
        Exception geminiError = null;
        if (has(cfg.apiKey)) {
            try {
                GeminiClient.Answer answer = GeminiClient.askAudio(ctx, cfg, wav);
                Log.i(TAG, "answered by Gemini");
                return answer;
            } catch (Exception e) {
                geminiError = e;
                Log.w(TAG, "Gemini failed: " + e.getMessage() + fallbackNote(cfg));
            }
        }
        if (has(cfg.groqKey)) {
            GeminiClient.Answer answer = GroqClient.askAudio(ctx, cfg, wav);
            Log.i(TAG, "answered by Groq");
            return answer;
        }
        throw geminiError != null ? geminiError : missingKey();
    }

    static String askText(Context ctx, Settings cfg, String text) throws Exception {
        Exception geminiError = null;
        if (has(cfg.apiKey)) {
            try {
                String answer = GeminiClient.askText(ctx, cfg, text);
                Log.i(TAG, "answered by Gemini");
                return answer;
            } catch (Exception e) {
                geminiError = e;
                Log.w(TAG, "Gemini failed: " + e.getMessage() + fallbackNote(cfg));
            }
        }
        if (has(cfg.groqKey)) {
            String answer = GroqClient.askText(ctx, cfg, text);
            Log.i(TAG, "answered by Groq");
            return answer;
        }
        throw geminiError != null ? geminiError : missingKey();
    }

    private static String fallbackNote(Settings cfg) {
        return has(cfg.groqKey) ? "; trying Groq" : "";
    }

    private static boolean has(String key) {
        return key != null && key.trim().length() > 0;
    }

    private static Exception missingKey() {
        return new Exception("No API key configured. Open S Voice Gemini Bridge and paste a key.");
    }
}
