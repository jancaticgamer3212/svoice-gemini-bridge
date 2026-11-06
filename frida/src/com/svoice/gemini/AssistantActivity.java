package com.svoice.gemini;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * No-root fallback: Android speech recognition plus Gemini and the same phone actions.
 * S Voice cannot be hooked without root, so this screen stands on its own.
 */
public class AssistantActivity extends Activity {
    private static final String TAG = "SVoiceGemini";

    private TextView log;
    private Button talk;
    private EditText input;
    private SpeechRecognizer recognizer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder history = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setText("No-root assistant");
        title.setTextSize(22);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("This screen works without root. S Voice is not hooked here; voice input uses "
                + "Android's speech recognition and answers are spoken with TTS.");
        note.setTextSize(12);
        note.setPadding(0, dp(4), 0, dp(10));
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        log = new TextView(this);
        log.setTextSize(15);
        log.setTextIsSelectable(true);
        log.setPadding(0, dp(6), 0, dp(6));
        scroll.addView(log);

        talk = new Button(this);
        talk.setText("Tap to talk");
        talk.setAllCaps(false);
        talk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listen();
            }
        });
        root.addView(talk, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);

        input = new EditText(this);
        input.setHint("Type a request");
        input.setSingleLine(true);
        row.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button send = new Button(this);
        send.setText("Send");
        send.setAllCaps(false);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (text.length() > 0) ask(text);
            }
        });
        row.addView(send);
        root.addView(row, matchWrap());

        setContentView(root);
        append("Ready. Tap the mic or type a request.");
    }

    private void listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            append("No speech recognition service. Type the request below instead.");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new Listener());
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        talk.setEnabled(false);
        talk.setText("Listening...");
        try {
            recognizer.startListening(intent);
        } catch (Throwable t) {
            talk.setEnabled(true);
            talk.setText("Tap to talk");
            append("Could not open the recognizer: " + t.getMessage());
        }
    }

    private void ask(final String text) {
        input.setText("");
        append("You: " + text);
        talk.setEnabled(false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final Settings cfg = Settings.load();
                if (!Assistant.hasAnyKey(cfg)) {
                    done("No API key. Open S Voice Gemini Bridge, paste a Gemini or Groq key, then try again.", false, cfg);
                    return;
                }
                try {
                    GeminiClient.Answer answer = Assistant.askText(AssistantActivity.this, cfg, text);
                    String extra = null;
                    if (answer.action != null) {
                        try {
                            DeviceActions.perform(getApplicationContext(), answer.action);
                        } catch (Throwable t) {
                            extra = t.getMessage();
                            Log.w(TAG, "action failed", t);
                        }
                    }
                    done(answer.answer + (extra == null ? "" : " " + extra), true, cfg);
                } catch (Throwable t) {
                    Log.w(TAG, "request failed", t);
                    String msg = t.getMessage();
                    done(msg == null || msg.length() == 0 ? "Request failed." : msg, false, cfg);
                }
            }
        });
    }

    private void done(final String text, final boolean speak, final Settings cfg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                append("Assistant: " + text);
                talk.setEnabled(true);
                talk.setText("Tap to talk");
                if (speak && cfg.speak) Tts.speak(AssistantActivity.this, text);
            }
        });
    }

    private void append(final String line) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (history.length() > 0) history.append('\n');
                history.append(line);
                log.setText(history.toString());
            }
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        Tts.stop();
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Throwable ignored) {
            }
            recognizer = null;
        }
        super.onDestroy();
    }

    private final class Listener implements RecognitionListener {
        @Override
        public void onResults(Bundle results) {
            talk.setEnabled(true);
            talk.setText("Tap to talk");
            List<String> matches = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches == null || matches.isEmpty()) {
                append("I didn't catch that. Tap the mic and try again.");
                return;
            }
            ask(matches.get(0));
        }

        @Override
        public void onError(int error) {
            talk.setEnabled(true);
            talk.setText("Tap to talk");
            if (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                append("I didn't catch that. Tap the mic and try again.");
            } else {
                append("Recognizer error " + error + ". Type the request below instead.");
            }
        }

        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onPartialResults(Bundle partialResults) {}
        @Override public void onEvent(int eventType, Bundle params) {}
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
