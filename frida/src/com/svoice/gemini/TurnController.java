package com.svoice.gemini;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.Locale;
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
                Outcome outcome = performAction(app, answer, answer.transcript);
                showAnswer(activity, app, cfg, outcome.answer.transcript, outcome.answer.answer,
                        outcome.cardMillis, id);
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
                GeminiClient.Answer result;
                try {
                    result = Assistant.askText(app, cfg, text);
                } catch (Throwable t) {
                    Log.w(TAG, "Assistant text request failed", t);
                    if (activeId == id) postMessage(activity, app, messageOf(t), true);
                    return;
                }
                if (activeId != id) return;
                Outcome outcome = performAction(app, result, text);
                showAnswer(activity, app, cfg, null, outcome.answer.answer, outcome.cardMillis, id);
            }
        });
    }

    /** A finished turn: the answer plus an optional S Voice countdown card to show. */
    private static final class Outcome {
        final GeminiClient.Answer answer;
        final long cardMillis;

        Outcome(GeminiClient.Answer answer, long cardMillis) {
            this.answer = answer;
            this.cardMillis = cardMillis;
        }
    }

    /**
     * Runs a model-requested phone action on the worker thread before the answer is shown.
     * A web search is only honoured when the user actually asked for one: otherwise a plain
     * question would launch the browser instead of being answered in the bubble.
     */
    private static Outcome performAction(Context app, GeminiClient.Answer answer, String userText) {
        if (answer == null || answer.action == null) return new Outcome(answer, 0L);
        if (isSearch(answer.action) && !asksToSearch(userText)) {
            Log.i(TAG, "answer only: the user did not ask for a web search");
            return new Outcome(answer, 0L);
        }
        try {
            DeviceActions.Result result = DeviceActions.perform(app, answer.action);
            if (result != null) Log.i(TAG, "action: " + result.log);
            GeminiClient.Answer shown = answer;
            if (result != null && result.say != null && result.say.length() > 0) {
                shown = new GeminiClient.Answer(answer.transcript, result.say, null);
            }
            return new Outcome(shown, result == null ? 0L : result.cardMillis);
        } catch (Throwable t) {
            String reason = messageOf(t);
            Log.w(TAG, "action failed: " + reason);
            String text = answer.answer;
            if (text.length() > 0) text = text + " ";
            return new Outcome(new GeminiClient.Answer(answer.transcript, text + reason, null), 0L);
        }
    }

    private static boolean isSearch(JSONObject action) {
        String name = action.optString("name", "");
        name = name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
        return name.equals("search") || name.equals("websearch") || name.equals("google")
                || name.equals("browse") || name.equals("lookup");
    }

    private static boolean asksToSearch(String text) {
        if (text == null || text.length() == 0) return false;
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("search") || lower.contains("google") || lower.contains("browse")
                || lower.contains("look up") || lower.contains("lookup") || lower.contains("online")
                || lower.contains("internet") || lower.contains("website") || lower.contains("web site");
    }

    private static void showAnswer(final Activity activity, final Context app, final Settings cfg,
                                   final String transcript, final String answer, final long cardMillis,
                                   final int id) {
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
                boolean shown;
                if (cardMillis > 0 && Ui.timerCard(activity, cardMillis)) {
                    shown = true;
                } else {
                    shown = Ui.bubble(activity, "VLINGO", answer);
                    if (!shown) Ui.toast(app, answer);
                }
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
