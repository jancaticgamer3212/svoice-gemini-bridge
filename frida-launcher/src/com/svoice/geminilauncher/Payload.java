package com.svoice.geminilauncher;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Maps the bundled assets and native libraries onto the root payload directory. */
final class Payload {
    static final String DIR = "/data/local/tmp/svoice-gemini";
    static final String[] SCRIPTS = {"bridge.js", "inject.sh", "watchdog.sh"};

    private static final String SO_NAME = "libfrida_inject.so";
    private static final String[][] INJECTORS = {
            {"arm64-v8a", "frida-inject64"},
            {"armeabi-v7a", "frida-inject32"},
    };

    private Payload() {
    }

    static String install(Context context) throws Exception {
        File cache = extractScripts(context);
        StringBuilder command = new StringBuilder();
        command.append("D=").append(DIR).append('\n');
        command.append("mkdir -p \"$D\" || exit 1\n");
        for (String name : SCRIPTS) {
            command.append("cp '").append(new File(cache, name).getAbsolutePath())
                    .append("' \"$D/").append(name).append("\" || exit 1\n");
        }
        for (String[] injector : INJECTORS) {
            File source = library(context, injector[0]);
            command.append("if [ -f '").append(source.getAbsolutePath()).append("' ]; then ")
                    .append("cp '").append(source.getAbsolutePath()).append("' \"$D/")
                    .append(injector[1]).append("\"; fi\n");
        }
        command.append("chmod 755 \"$D\"/* || exit 1\n");
        command.append("[ -f \"$D/frida-inject64\" ] || [ -f \"$D/frida-inject32\" ] || ")
                .append("{ echo 'this APK has no Frida injector for this CPU'; exit 3; }\n");
        command.append("ls -l \"$D\"\n");

        RootShell.Result result = RootShell.run(command.toString());
        if (!result.ok()) {
            throw new Exception(firstLine(result));
        }
        return result.output;
    }

    static RootShell.Result status() throws Exception {
        return RootShell.run(
                "D=" + DIR + "\n"
                + "i=0\n"
                + "for f in bridge.js inject.sh watchdog.sh frida-inject32 frida-inject64; do "
                + "[ -f \"$D/$f\" ] && i=$((i + 1)); done\n"
                + "echo payload=$i\n"
                + "[ -f /sdcard/.svoice_gemini.json ] && echo settings=1 || echo settings=0\n"
                + "P=\n"
                + "for f in /proc/[0-9]*/cmdline; do\n"
                + "  c=$(tr '\\0' ' ' <\"$f\" 2>/dev/null)\n"
                + "  case \"$c\" in \"com.vlingo.midas\"|\"com.vlingo.midas \"*) "
                + "P=${f#/proc/}; P=${P%/cmdline}; break;; esac\n"
                + "done\n"
                + "echo pid=$P\n"
                + "if [ -n \"$P\" ]; then\n"
                + "  L=$(ls -l \"/proc/$P/exe\" 2>/dev/null)\n"
                + "  case \"$L\" in *app_process64*) echo abi=64;; "
                + "*app_process32*) echo abi=32;; *) echo abi=?;; esac\n"
                + "  grep -q frida \"/proc/$P/maps\" 2>/dev/null && echo attached=1 || echo attached=0\n"
                + "else\n"
                + "  echo abi=?\n"
                + "  echo attached=0\n"
                + "fi\n");
    }

    static String firstLine(RootShell.Result result) {
        String text = result.output;
        int cut = text.indexOf('\n');
        if (cut > 0) {
            text = text.substring(0, cut);
        }
        text = text.trim();
        if (text.length() == 0) {
            return "su refused the command (exit " + result.code + ")";
        }
        return text;
    }

    private static File extractScripts(Context context) throws Exception {
        File dir = new File(context.getCacheDir(), "payload");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new Exception("cannot create " + dir);
        }
        for (String name : SCRIPTS) {
            copy(context.getAssets().open(name), new File(dir, name));
        }
        return dir;
    }

    private static void copy(InputStream in, File out) throws Exception {
        try {
            OutputStream outStream = new FileOutputStream(out);
            try {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    outStream.write(buffer, 0, n);
                }
            } finally {
                outStream.close();
            }
        } finally {
            in.close();
        }
    }

    /**
     * The package manager extracts the matching ABIs next to the primary one, e.g.
     * /data/app/<pkg>/lib/arm64-v8a and /data/app/<pkg>/lib/armeabi-v7a.
     */
    private static File library(Context context, String abi) {
        File primary = new File(context.getApplicationInfo().nativeLibraryDir);
        if (primary.getName().equals(abi)) {
            return new File(primary, SO_NAME);
        }
        return new File(new File(primary.getParentFile(), abi), SO_NAME);
    }
}
