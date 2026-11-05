package com.svoice.gemini;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class TurnController {
    private static final String TAG = "SVoiceGemini";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static volatile int activeId;
    private static volatile Recorder.Turn activeRecording;

    private TurnController() {}

    static void startVoice(final Activity activity, final Context app, final Settings cfg) {
        final int id = SEQ.incrementAndGet();
        activeId = id;
        cancelRecording();
        Tts.stop();

        if (!Assistant.hasAnyKey(cfg)) {
            postMessage(activity, app, "No API key. Open S Voice Gemini Bridge, paste a Gemini or Groq key, then tap the mic again.", true);
            return;
        }

        // The caller sets ASR_GETTING_READY right after we return, so switch to
        // the listening look slightly later.
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activeId == id) Ui.setState(activity, "ASR_LISTENING");
            }
        }, 400);

        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                byte[] wav;
                try {
                    Recorder.Turn turn = new Recorder.Turn();
                    activeRecording = turn;
                    wav = Recorder.record(turn);
                } catch (Throwable t) {
                    Log.w(TAG, "recording failed", t);
                    if (activeId == id) {
                        String msg = t.getMessage();
                        if (msg == null || msg.length() == 0) msg = "Could not open the microphone.";
                        postMessage(activity, app, msg, false);
                    }
                    return;
                } finally {
                    activeRecording = null;
                }

                if (activeId != id) return;
                if (wav == null) {
                    postMessage(activity, app, "I didn't catch that. Tap the mic and try again.", false);
                    return;
                }

                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (activeId == id) Ui.setState(activity, "ASR_THINKING");
                    }
                });

                GeminiClient.Answer answer;
                try {
                    answer = Assistant.askAudio(app, cfg, wav);
                } catch (Throwable t) {
                    Log.w(TAG, "Assistant request failed", t);
                    if (activeId == id) {
                        postMessage(activity, app, messageOf(t), true);
                    }
                    return;
                }
                if (activeId != id) return;
                showAnswer(activity, app, cfg, answer.transcript, answer.answer, id);
            }
        });
    }

    static void startText(final Activity activity, final Context app, final Settings cfg, final String text) {
        final int id = SEQ.incrementAndGet();
        activeId = id;
        cancelRecording();
        Tts.stop();

        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (activeId != id) return;
                Ui.bubble(activity, "USER", text);
                Ui.setState(activity, "ASR_THINKING");
            }
        });

        if (!Assistant.hasAnyKey(cfg)) {
            postMessage(activity, app, "No API key. Open S Voice Gemini Bridge, paste a Gemini or Groq key, then try again.", true);
            return;
        }

        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                String answer;
                try {
                    answer = Assistant.askText(app, cfg, text);
                } catch (Throwable t) {
                    Log.w(TAG, "Assistant text request failed", t);
                    if (activeId == id) postMessage(activity, app, messageOf(t), true);
                    return;
                }
                if (activeId != id) return;
                showAnswer(activity, app, cfg, null, answer, id);
            }
        });
    }

    private static void showAnswer(final Activity activity, final Context app, final Settings cfg,
                                   final String transcript, final String answer, final int id) {
        if (answer == null || answer.length() == 0) {
            postMessage(activity, app, "Gemini returned an empty answer.", false);
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (activeId != id) return;
                Ui.retireLastWidget(activity);
                if (transcript != null && transcript.length() > 0) {
                    Ui.bubble(activity, "USER", transcript);
                }
                boolean shown = Ui.bubble(activity, "VLINGO", answer);
                if (!shown) Ui.toast(app, answer);
                if (cfg.speak && shown) Tts.speak(app, answer);
                finish(activity);
            }
        });
    }

    private static void postMessage(final Activity activity, final Context app, final String message, final boolean toast) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                String text = message == null || message.length() == 0 ? "Gemini request failed." : message;
                boolean shown = Ui.bubble(activity, "ERROR", text);
                if (!shown || toast) Ui.toast(app, text);
                finish(activity);
            }
        });
    }

    private static void finish(final Activity activity) {
        Ui.notifyRecorderClosed(activity);
        Ui.setState(activity, "IDLE");
    }

    private static void cancelRecording() {
        Recorder.Turn turn = activeRecording;
        if (turn != null) turn.cancelled = true;
    }

    private static String messageOf(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.length() == 0) msg = t.getClass().getSimpleName();
        return msg;
    }
}
