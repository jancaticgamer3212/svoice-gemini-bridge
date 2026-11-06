package com.svoice.gemini;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

final class Tts {
    private static TextToSpeech engine;
    private static boolean ready;
    private static boolean failed;
    private static String pending;

    private Tts() {}

    /** Must be called on the main thread. */
    static void speak(Context ctx, String text) {
        if (ctx == null || text == null || text.length() == 0 || failed) return;
        pending = text;
        if (engine == null) {
            try {
                engine = new TextToSpeech(ctx.getApplicationContext(), new TextToSpeech.OnInitListener() {
                    @Override
                    public void onInit(int status) {
                        if (status == TextToSpeech.SUCCESS) {
                            ready = true;
                            flush();
                        } else {
                            failed = true;
                            pending = null;
                        }
                    }
                });
            } catch (Throwable t) {
                failed = true;
            }
        } else if (ready) {
            flush();
        }
    }

    static void stop() {
        pending = null;
        if (engine != null && ready) {
            try { engine.stop(); } catch (Throwable ignored) {}
        }
    }

    private static void flush() {
        if (!ready || engine == null || pending == null) return;
        String text = pending;
        pending = null;
        try {
            engine.setLanguage(Locale.getDefault());
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, (Bundle) null, "svoice-gemini");
            } else {
                // The four-argument speak() is API 21.
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Throwable ignored) {
        }
    }
}
