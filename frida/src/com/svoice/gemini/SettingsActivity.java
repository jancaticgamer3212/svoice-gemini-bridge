package com.svoice.gemini;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends Activity {
    private CheckBox enabled;
    private EditText apiKey;
    private EditText model;
    private EditText groqKey;
    private EditText groqModel;
    private EditText systemPrompt;
    private CheckBox speak;
    private TextView testResult;
    private TextView setupStatus;
    private TextView setupLog;
    private Button installButton;
    private Button attachButton;
    private Button fallbackButton;

    private final ExecutorService setupWorker = Executors.newSingleThreadExecutor();

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

        root.addView(label("Bridge control", 18, true));

        setupStatus = label("Checking...", 12, false);
        setupStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        setupStatus.setPadding(0, dp(8), 0, dp(12));
        root.addView(setupStatus);

        installButton = new Button(this);
        installButton.setText("Install Frida payload (root)");
        installButton.setAllCaps(false);
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installPayload();
            }
        });
        root.addView(installButton, matchWrap());

        attachButton = new Button(this);
        attachButton.setText("Attach to S Voice now");
        attachButton.setAllCaps(false);
        attachButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attachNow();
            }
        });
        root.addView(attachButton, matchWrap());

        fallbackButton = new Button(this);
        fallbackButton.setText("Open no-root assistant (fallback)");
        fallbackButton.setAllCaps(false);
        fallbackButton.setVisibility(View.GONE);
        fallbackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, AssistantActivity.class));
            }
        });
        root.addView(fallbackButton, matchWrap());

        TextView setupHelp = label(
                "The Frida injectors and scripts are inside this APK. Install the payload once, "
                + "allow the su request, then attach. The watchdog reattaches on its own when "
                + "S Voice restarts, after an update and after a reboot. Without root the S Voice "
                + "bridge cannot load; the no-root assistant above is used instead.",
                12, false);
        setupHelp.setPadding(0, dp(6), 0, dp(10));
        root.addView(setupHelp);

        setupLog = label("", 11, false);
        setupLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        setupLog.setPadding(0, dp(4), 0, dp(18));
        setupLog.setTextIsSelectable(true);
        setupLog.setVisibility(View.GONE);
        root.addView(setupLog);

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
                "1. This app stores the settings, provides the bridge code and carries the "
                + "Frida 12.8.20 injectors.\n"
                + "2. Enter the Gemini API key above and tap Save.\n"
                + "3. On a rooted phone tap Install Frida payload (root) once, then Attach to "
                + "S Voice now.\n"
                + "4. Open S Voice and tap the microphone. The Hi Galaxy wake word is left untouched.\n\n"
                + "The watchdog reattaches by itself when S Voice restarts, after an APK update "
                + "and after a reboot. frida/push-and-run.sh still works from a PC if you prefer.",
                14, false);
        steps.setPadding(0, dp(6), 0, dp(30));
        root.addView(steps);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSetup();
    }

    @Override
    protected void onDestroy() {
        setupWorker.shutdownNow();
        super.onDestroy();
    }

    private void installPayload() {
        runSetup("Installing the Frida payload", new SetupTask() {
            @Override
            public String call() throws Exception {
                return Payload.install(SettingsActivity.this);
            }
        });
    }

    private void attachNow() {
        runSetup("Attaching to S Voice", new SetupTask() {
            @Override
            public String call() throws Exception {
                RootShell.Result result = RootShell.run("sh " + Payload.DIR + "/inject.sh");
                if (!result.ok()) {
                    throw new Exception(Payload.firstLine(result));
                }
                return result.output;
            }
        });
    }

    private void runSetup(final String what, final SetupTask task) {
        setSetupBusy(true);
        setupLog.setVisibility(View.VISIBLE);
        setupLog.setText(what + "...");
        setupWorker.execute(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    text = tail(task.call());
                } catch (Exception e) {
                    text = what + " failed: " + e.getMessage();
                }
                final String rendered = text;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setupLog.setText(rendered);
                        setSetupBusy(false);
                    }
                });
                refreshSetup();
            }
        });
    }

    private void refreshSetup() {
        final String svoice = packageVersion("com.vlingo.midas");
        setupWorker.execute(new Runnable() {
            @Override
            public void run() {
                StringBuilder text = new StringBuilder();
                Map<String, String> values = new HashMap<String, String>();
                boolean root = false;
                try {
                    RootShell.Result result = Payload.status();
                    root = result.ok();
                    values = parseValues(result.output);
                } catch (Exception e) {
                    text.append("Root: not available (").append(e.getMessage()).append(")\n");
                }

                if (root) {
                    text.append("Root: granted\n");
                } else if (text.length() == 0) {
                    text.append("Root: denied or timed out\n");
                }
                text.append("S Voice: ").append(svoice == null ? "missing" : svoice).append('\n');

                String pid = values.get("pid");
                String abi = values.get("abi");
                if (pid == null || pid.length() == 0) {
                    text.append("S Voice running: no\n");
                } else {
                    text.append("S Voice running: pid ").append(pid)
                            .append(abi != null && !"?".equals(abi) ? " (" + abi + "-bit)" : "")
                            .append('\n');
                }
                text.append("Bridge attached: ")
                        .append("1".equals(values.get("attached")) ? "yes" : "no")
                        .append('\n');

                if (root) {
                    int scripts = parseCount(values.get("scripts"));
                    int injectors = parseCount(values.get("injectors"));
                    text.append("Payload: ");
                    if (scripts == 3 && injectors > 0) {
                        text.append("installed (").append(scripts).append("/3 scripts, ")
                                .append(injectors).append(" injector")
                                .append(injectors == 1 ? "" : "s").append(")\n");
                    } else {
                        text.append("missing - tap Install Frida payload above\n");
                    }
                    text.append("API key file: ")
                            .append("1".equals(values.get("settings"))
                                    ? "present"
                                    : "missing - enter the key and tap Save")
                            .append('\n');
                }

                final String rendered = text.toString();
                final boolean hasRoot = root;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setupStatus.setText(rendered);
                        fallbackButton.setVisibility(hasRoot ? View.GONE : View.VISIBLE);
                    }
                });
            }
        });
    }

    private String packageVersion(String packageName) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(packageName, 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void setSetupBusy(boolean busy) {
        installButton.setEnabled(!busy);
        attachButton.setEnabled(!busy);
    }

    private static String tail(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 4000) {
            return text.substring(text.length() - 4000);
        }
        return text;
    }

    private static Map<String, String> parseValues(String output) {
        Map<String, String> values = new HashMap<String, String>();
        for (String line : output.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq), line.substring(eq + 1).trim());
            }
        }
        return values;
    }

    private static int parseCount(String raw) {
        try {
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private interface SetupTask {
        String call() throws Exception;
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
