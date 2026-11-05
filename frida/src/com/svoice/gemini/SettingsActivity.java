package com.svoice.gemini;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class SettingsActivity extends Activity {
    private CheckBox enabled;
    private EditText apiKey;
    private EditText model;
    private EditText groqKey;
    private EditText groqModel;
    private EditText systemPrompt;
    private CheckBox speak;
    private TextView testResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("S Voice Gemini Bridge");
        WatchdogService.start(this);

        Settings current = Settings.load();

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(label("Status", 18, true));

        TextView intro = label(
                "S Voice keeps its own UI and microphone. This module replaces only the cloud "
                + "recognition step with Google Gemini, so microphone taps and typed requests "
                + "are answered by Gemini instead of the retired Vlingo service.",
                14, false);
        intro.setPadding(0, dp(6), 0, dp(14));
        root.addView(intro);

        enabled = new CheckBox(this);
        enabled.setText("Enable Gemini bridge");
        enabled.setChecked(current.enabled);
        root.addView(enabled);

        root.addView(label("Gemini API key", 18, true));
        apiKey = new EditText(this);
        apiKey.setHint("AIza...");
        apiKey.setSingleLine(true);
        apiKey.setText(current.apiKey);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        root.addView(apiKey, matchWrap());

        TextView keyHelp = label(
                "Create a key in Google AI Studio (aistudio.google.com/app/apikey). "
                + "The key is stored on this phone only and never bundled into an APK.",
                12, false);
        keyHelp.setPadding(0, dp(4), 0, dp(14));
        root.addView(keyHelp);

        root.addView(label("Model", 18, true));
        model = new EditText(this);
        model.setHint(Settings.DEFAULT_MODEL);
        model.setSingleLine(true);
        model.setText(current.model);
        model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(model, matchWrap());

        root.addView(label("Groq fallback (free)", 18, true));
        groqKey = new EditText(this);
        groqKey.setHint("gsk_...");
        groqKey.setSingleLine(true);
        groqKey.setText(current.groqKey);
        groqKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        root.addView(groqKey, matchWrap());

        TextView groqHelp = label(
                "Optional and free. Create a key at console.groq.com/keys. It is used only when "
                + "Gemini cannot answer (busy, no quota, wrong model). Leave it empty to use Gemini alone.",
                12, false);
        groqHelp.setPadding(0, dp(4), 0, dp(14));
        root.addView(groqHelp);

        root.addView(label("Groq model", 18, true));
        groqModel = new EditText(this);
        groqModel.setHint(Settings.DEFAULT_GROQ_MODEL);
        groqModel.setSingleLine(true);
        groqModel.setText(current.groqModel);
        groqModel.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(groqModel, matchWrap());

        root.addView(label("Personality / system prompt", 18, true));
        systemPrompt = new EditText(this);
        systemPrompt.setGravity(Gravity.TOP | Gravity.START);
        systemPrompt.setMinLines(4);
        systemPrompt.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        systemPrompt.setText(current.systemPrompt);
        root.addView(systemPrompt, matchWrap());

        speak = new CheckBox(this);
        speak.setText("Speak answers with Android TTS");
        speak.setChecked(current.speak);
        speak.setPadding(0, dp(12), 0, dp(12));
        root.addView(speak);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndClose();
            }
        });
        buttons.addView(save, weightWrap(1));

        Button test = new Button(this);
        test.setText("Test key");
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testKey();
            }
        });
        buttons.addView(test, weightWrap(1));

        root.addView(buttons, matchWrap());

        Button testGroq = new Button(this);
        testGroq.setText("Test Groq");
        testGroq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testGroqKey();
            }
        });
        root.addView(testGroq, matchWrap());

        testResult = label("", 13, false);
        testResult.setPadding(0, dp(10), 0, dp(16));
        root.addView(testResult);

        root.addView(label("Installation", 18, true));
        TextView steps = label(
                "1. Install this app. It stores the settings and provides the bridge code.\n"
                + "2. Enter the Gemini API key above and tap Save.\n"
                + "3. On a rooted phone, run frida/push-and-run.sh from the project folder. "
                + "It attaches bridge.js to S Voice with Frida 12.8.20.\n"
                + "4. Open S Voice and tap the microphone. The Hi Galaxy wake word is left untouched.\n\n"
                + "The bridge runs while the Frida script is attached; run the script again after "
                + "a reboot or a force-stop of S Voice.",
                14, false);
        steps.setPadding(0, dp(6), 0, dp(30));
        root.addView(steps);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void saveAndClose() {
        String key = apiKey.getText().toString().trim();
        String modelName = model.getText().toString().trim();
        if (modelName.length() == 0) modelName = Settings.DEFAULT_MODEL;
        String groq = groqKey.getText().toString().trim();
        String groqModelName = groqModel.getText().toString().trim();
        if (groqModelName.length() == 0) groqModelName = Settings.DEFAULT_GROQ_MODEL;
        String prompt = systemPrompt.getText().toString().trim();
        if (prompt.length() == 0) prompt = Settings.DEFAULT_SYSTEM_PROMPT;

        SharedPreferences prefs = getSharedPreferences(Settings.PREFS_NAME, MODE_WORLD_READABLE);
        prefs.edit()
                .putBoolean("enabled", enabled.isChecked())
                .putString("api_key", key)
                .putString("model", modelName)
                .putString("groq_key", groq)
                .putString("groq_model", groqModelName)
                .putString("system_prompt", prompt)
                .putBoolean("speak", speak.isChecked())
                .commit();
        relaxPermissions();

        try {
            JSONObject json = new JSONObject();
            json.put("enabled", enabled.isChecked());
            json.put("apiKey", key);
            json.put("model", modelName);
            json.put("groqKey", groq);
            json.put("groqModel", groqModelName);
            json.put("systemPrompt", prompt);
            json.put("speak", speak.isChecked());
            writeExternal(json.toString());
        } catch (Throwable ignored) {
        }

        Toast.makeText(this, "Saved. Open S Voice and tap the mic.", Toast.LENGTH_LONG).show();
        finish();
    }

    private void testKey() {
        String key = apiKey.getText().toString().trim();
        if (key.length() == 0) {
            testResult.setText("Enter an API key first.");
            return;
        }
        Settings cfg = new Settings();
        cfg.apiKey = key;
        cfg.model = model.getText().toString().trim();
        if (cfg.model.length() == 0) cfg.model = Settings.DEFAULT_MODEL;
        cfg.systemPrompt = "You are a connectivity test. Answer with one word.";

        testResult.setText("Contacting Gemini...");
        final Settings cfgFinal = cfg;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    String reply = GeminiClient.testKey(SettingsActivity.this, cfgFinal);
                    message = "Success. Gemini replied: " + reply;
                } catch (Throwable t) {
                    message = "Failed: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                }
                final String text = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        testResult.setText(text);
                    }
                });
            }
        }).start();
    }

    private void testGroqKey() {
        String key = groqKey.getText().toString().trim();
        if (key.length() == 0) {
            testResult.setText("Enter a Groq API key first.");
            return;
        }
        Settings cfg = Settings.load();
        cfg.groqKey = key;
        cfg.groqModel = groqModel.getText().toString().trim();
        if (cfg.groqModel.length() == 0) cfg.groqModel = Settings.DEFAULT_GROQ_MODEL;

        testResult.setText("Contacting Groq...");
        final Settings cfgFinal = cfg;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    String reply = GroqClient.testKey(SettingsActivity.this, cfgFinal);
                    message = "Success. Groq replied: " + reply;
                } catch (Throwable t) {
                    message = "Failed: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                }
                final String text = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        testResult.setText(text);
                    }
                });
            }
        }).start();
    }

    private void relaxPermissions() {
        try {
            File dataDir = new File(getApplicationInfo().dataDir);
            dataDir.setExecutable(true, false);
            File prefsDir = new File(dataDir, "shared_prefs");
            prefsDir.setExecutable(true, false);
            prefsDir.setReadable(true, false);
            File prefsFile = new File(prefsDir, Settings.PREFS_NAME + ".xml");
            prefsFile.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private void writeExternal(String json) throws Exception {
        File f = Settings.externalFile();
        if (f == null) return;
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        f.setReadable(true, false);
    }

    private TextView label(String text, int sizeSp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        v.setTextColor(Color.parseColor("#202124"));
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap(int weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
