package com.svoice.geminilauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BRIDGE_PACKAGE = "com.svoice.gemini";
    private static final String SETTINGS_ACTIVITY = "com.svoice.gemini.SettingsActivity";
    private static final String WATCHDOG_ACTION = "com.svoice.gemini.START_WATCHDOG";
    private static final String WATCHDOG_RECEIVER = "com.svoice.gemini.BootReceiver";
    private static final String SVOICE_PACKAGE = "com.vlingo.midas";
    private static final String INJECT_SCRIPT = Payload.DIR + "/inject.sh";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView logView;
    private Button installButton;
    private Button startButton;
    private Button autoButton;
    private Button settingsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("S Voice Frida Launcher");

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(label("This phone", 18, true));
        statusView = label("Checking...", 13, false);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setPadding(0, dp(8), 0, dp(16));
        root.addView(statusView);

        installButton = button(root, "1. Install Frida payload (root)", new Task() {
            @Override
            public String call() throws Exception {
                String output = Payload.install(MainActivity.this);
                return "Payload installed in " + Payload.DIR + ":\n" + output;
            }
        });
        startButton = button(root, "2. Start bridge now", new Task() {
            @Override
            public String call() throws Exception {
                return startBridge();
            }
        });
        autoButton = button(root, "3. Keep it attached after restarts", new Task() {
            @Override
            public String call() throws Exception {
                return armWatchdog();
            }
        });
        settingsButton = button(root, "4. Open bridge settings (API key)", new Task() {
            @Override
            public String call() throws Exception {
                return openSettings();
            }
        });

        TextView help = label(
                "Steps 1 and 2 need the S-Voice-Gemini-Frida.apk bridge app installed: it holds the "
                + "API key and the Gemini client. Approve the root request from your su manager when "
                + "it appears. Step 2 starts S Voice and hooks it once; step 3 makes the bridge "
                + "reattach by itself when S Voice restarts or the phone reboots.",
                12, false);
        help.setPadding(0, dp(14), 0, dp(14));
        root.addView(help);

        root.addView(label("Last result", 18, true));
        logView = label("", 11, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(0, dp(8), 0, dp(20));
        logView.setTextIsSelectable(true);
        root.addView(logView);

        TextView notice = label(
                "Bundles Frida 12.8.20 (wxWindows Library Licence). See THIRD-PARTY-NOTICES.txt "
                + "in the project repository.",
                10, false);
        notice.setPadding(0, dp(10), 0, 0);
        root.addView(notice);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private String startBridge() throws Exception {
        if (version(BRIDGE_PACKAGE) == null) {
            throw new Exception("install S-Voice-Gemini-Frida.apk first");
        }
        RootShell.Result result = RootShell.run("sh " + INJECT_SCRIPT);
        if (!result.ok()) {
            throw new Exception(Payload.firstLine(result));
        }
        String output = result.output;
        if (output.length() > 6000) {
            output = output.substring(output.length() - 6000);
        }
        return "Bridge started.\n\n" + output;
    }

    private String armWatchdog() throws Exception {
        if (version(BRIDGE_PACKAGE) == null) {
            throw new Exception("install S-Voice-Gemini-Frida.apk first");
        }
        Intent probe = new Intent(WATCHDOG_ACTION).setPackage(BRIDGE_PACKAGE);
        if (getPackageManager().queryBroadcastReceivers(probe, 0).isEmpty()) {
            throw new Exception("update S-Voice-Gemini-Frida.apk to 2.4 or newer");
        }
        Intent intent = new Intent(WATCHDOG_ACTION);
        intent.setComponent(new ComponentName(BRIDGE_PACKAGE, WATCHDOG_RECEIVER));
        sendBroadcast(intent);
        return "The bridge app now keeps the hook attached. A notification stays in the shade while "
                + "it runs, and the reattach survives S Voice restarts, app updates and reboots.";
    }

    private String openSettings() throws Exception {
        if (version(BRIDGE_PACKAGE) == null) {
            throw new Exception("install S-Voice-Gemini-Frida.apk first");
        }
        Intent intent = new Intent();
        intent.setClassName(BRIDGE_PACKAGE, SETTINGS_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        return "Opened the bridge settings.";
    }

    private void refreshStatus() {
        final String bridge = version(BRIDGE_PACKAGE);
        final String svoice = version(SVOICE_PACKAGE);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                StringBuilder text = new StringBuilder();
                Map<String, String> values = new HashMap<String, String>();
                boolean root = false;
                try {
                    RootShell.Result result = Payload.status();
                    root = result.ok();
                    values = parse(result.output);
                } catch (Exception e) {
                    text.insert(0, "Root: not available (" + e.getMessage() + ")\n");
                }

                if (root) {
                    text.append("Root: granted\n");
                } else if (text.length() == 0) {
                    text.append("Root: denied or timed out\n");
                }
                text.append("Bridge app: ")
                        .append(bridge == null
                                ? "missing - install S-Voice-Gemini-Frida.apk"
                                : bridge)
                        .append('\n');
                text.append("S Voice: ")
                        .append(svoice == null ? "missing" : svoice)
                        .append('\n');

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
                    int files = parseCount(values.get("payload"));
                    text.append("Payload: ").append(files).append("/5 files")
                            .append(files == 5 ? "" : " - tap step 1")
                            .append('\n');
                    text.append("API key file: ")
                            .append("1".equals(values.get("settings"))
                                    ? "present"
                                    : "missing - tap step 4 and press Save")
                            .append('\n');
                }

                final String rendered = text.toString();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        statusView.setText(rendered);
                    }
                });
            }
        });
    }

    private void run(final String what, final Task task) {
        setButtonsEnabled(false);
        logView.setText(what + "...");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    message = task.call();
                } catch (Exception e) {
                    message = what + " failed: " + e.getMessage();
                    toastAsync(what + " failed");
                }
                final String rendered = message;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        logView.setText(rendered);
                        setButtonsEnabled(true);
                    }
                });
                refreshStatus();
            }
        });
    }

    private void toastAsync(final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setButtonsEnabled(boolean enabled) {
        installButton.setEnabled(enabled);
        startButton.setEnabled(enabled);
        autoButton.setEnabled(enabled);
        settingsButton.setEnabled(enabled);
    }

    private String version(String packageName) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(packageName, 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static Map<String, String> parse(String output) {
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

    private Button button(LinearLayout parent, String text, final Task task) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                run(text, task);
            }
        });
        parent.addView(button, matchWrap());
        return button;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private interface Task {
        String call() throws Exception;
    }
}
