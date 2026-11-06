package com.svoice.gemini;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Maps the bundled assets and native libraries onto the root payload directory. */
final class Payload {
    static final String DIR = "/data/local/tmp/svoice-gemini";
    static final String[] SCRIPTS = {"bridge.js", "inject.sh", "watchdog.sh"};

    private static final String SO_NAME = "libfrida_inject.so";

    /**
     * Output name followed by the ABI directory names to look for. Android 5.x uses short
     * names (/data/app/<pkg>/lib/arm, .../lib/arm64), newer releases use the full ABI names.
     */
    private static final String[][] INJECTORS = {
            {"frida-inject64", "arm64-v8a", "arm64"},
            {"frida-inject32", "armeabi-v7a", "arm", "armeabi"},
    };

    private Payload() {
    }

    static String install(Context context) throws Exception {
        File cache = extractScripts(context);
        List<File> sources = new ArrayList<File>();
        List<String> names = new ArrayList<String>();
        for (String[] injector : INJECTORS) {
            File source = injectorSource(context, injector);
            if (source != null) {
                sources.add(source);
                names.add(injector[0]);
            }
        }
        StringBuilder command = new StringBuilder();
        command.append("D=").append(DIR).append('\n');
        command.append("mkdir -p \"$D\" || exit 1\n");
        for (String name : SCRIPTS) {
            command.append("cp '").append(new File(cache, name).getAbsolutePath())
                    .append("' \"$D/").append(name).append("\" || exit 1\n");
        }
        for (int i = 0; i < sources.size(); i++) {
            command.append("cp '").append(sources.get(i).getAbsolutePath())
                    .append("' \"$D/").append(names.get(i)).append("\" || exit 1\n");
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
                + "s=0\n"
                + "for f in bridge.js inject.sh watchdog.sh; do "
                + "[ -f \"$D/$f\" ] && s=$((s + 1)); done\n"
                + "o=0\n"
                + "for f in frida-inject32 frida-inject64; do "
                + "[ -f \"$D/$f\" ] && o=$((o + 1)); done\n"
                + "echo scripts=$s\n"
                + "echo injectors=$o\n"
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
                + "*app_process32*) echo abi=32;; *) echo abi=32;; esac\n"
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
     * PackageManager extracts only the ABIs the device supports, so at most one of these
     * directories exists. Fall back to reading the library straight out of the APK.
     */
    private static File injectorSource(Context context, String[] injector) {
        File libRoot = new File(context.getApplicationInfo().nativeLibraryDir).getParentFile();
        for (int i = 1; i < injector.length; i++) {
            File candidate = new File(new File(libRoot, injector[i]), SO_NAME);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        for (int i = 1; i < injector.length; i++) {
            File extracted = extractFromApk(context, injector[i], injector[0]);
            if (extracted != null) {
                return extracted;
            }
        }
        return null;
    }

    private static File extractFromApk(Context context, String abi, String name) {
        ZipFile apk = null;
        try {
            apk = new ZipFile(context.getPackageCodePath());
            ZipEntry entry = apk.getEntry("lib/" + abi + "/" + SO_NAME);
            if (entry == null) {
                return null;
            }
            File out = new File(new File(context.getCacheDir(), "payload"), name);
            InputStream in = apk.getInputStream(entry);
            try {
                copy(in, out);
            } finally {
                in.close();
            }
            return out;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (apk != null) {
                try {
                    apk.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
