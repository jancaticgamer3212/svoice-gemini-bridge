# Using the bridge on other devices

The bridge itself is not tied to the Galaxy S6 edge. It hooks classes that ship **inside the
S Voice APK**, so any device that can run that same S Voice build can run the bridge. The
constraints all come from S Voice, which is a 2014 TouchWiz app.

## Why there is no S Voice APK in this repository

This project publishes **only its own code**. S Voice (`com.vlingo.midas`) is Samsung
proprietary software: redistributing the APK is a copyright problem, and the APK also contains
Samsung service credentials that must not be republished.

To use the bridge on another phone, dump S Voice from a Samsung device you own (step 1 below)
and sideload it yourself. That is a personal-use copy, not a redistribution.

## What works where

S Voice 11.8.0.0 is `minSdkVersion 16`, `targetSdkVersion 19`, and declares a large set of
`com.samsung.*` / `com.sec.*` permissions and intents. It expects Samsung's system services
(voice wake-up, Memo, Calendar, Clock/alarm, Samsung Music, SNS).

| Target | Expectation |
| --- | --- |
| S6 / S6 edge / Note 5 era Samsung, Android 5.x–7.x | Closest to the original; full bridge behaviour |
| Other Samsung, Android 8–9 | Core tap-to-talk flow usually works; wake word and Samsung app integrations degrade |
| Non-Samsung, Android 5–9 | S Voice installs and the voice flow can work; every Samsung-only integration is gone, wake word almost certainly not functional |
| Android 10 | Installs and attaches; Frida 12.8.20 still works here |
| Android 11+ | Installs (the APK is pure Java), but needs a newer Frida release and a port of `bridge.js` |

The bridge has only been exercised against **11.8.0.0** and **11.9.0.0**. Other versions will
need `frida/bridge.js` re-checked — the hook target is
`DialogFlow.startUserFlow(RecognitionMode, WakeUpContext, IAudioStream)`.

## 1. Pull your own copy

Connect the Samsung phone that has S Voice working (USB debugging on), then from this folder:

```bash
bash frida/dump-svoice.sh
```

The script requires exactly one connected device, reads the installed version, pulls the APK to
`./SVoice.apk`, warns if the version is not one the bridge knows, and prints the native ABIs,
size and SHA-256.

Reference run against the S6 edge:

```
Found S Voice 11.8.0.0 at /system/priv-app/S-Voice_Android_phone/S-Voice_Android_phone.apk
Version 11.8.0.0 matches the builds the bridge was written for.
Native ABIs: none (pure Java)
Size: 8258563 bytes
SHA-256: 18b70219d22b0fb9d03dc2fe7243eb8f15d82734d15a65790a66e43da6e08b48
```

Because this build carries **no native libraries**, the CPU architecture of the target device
does not matter for S Voice. Only the Frida injector is architecture-specific, and
`push-and-run.sh` pushes both builds and picks the right one.

Manual equivalent, if you prefer:

```bash
adb shell dumpsys package com.vlingo.midas | grep versionName
adb shell pm path com.vlingo.midas          # prints the real path for your firmware
adb pull /system/priv-app/S-Voice_Android_phone/S-Voice_Android_phone.apk ./SVoice.apk
```

The pulled APK keeps Samsung's signature. `adb install` does not care whose certificate it is.

## 2. Install on the target device

```bash
adb install -r SVoice.apk
adb install -r S-Voice-Gemini-Frida.apk
```

Things to know:

* On the original phone S Voice is a **system app**; here it is a user app. Permissions with
  `protectionLevel="signatureOrSystem"` (backup restore, some Samsung integrations) are denied
  and those features quietly fail. The microphone path is not one of them.
* S Voice targets API 19, so on Android 6+ its permissions are granted at install time, not
  requested at runtime.
* If S Voice refuses to start on a non-Samsung device, it is missing a Samsung framework
  service and there is no fix short of a Samsung ROM. Fall back to a standalone assistant app.

## 3. Configure the key

Open **S Voice Gemini Bridge**, paste a Gemini key (or a Groq key for the free fallback), press
**Test key**, then **Save**. This writes `/sdcard/.svoice_gemini.json`, which the bridge reads on
every turn.

To reuse the keys from another phone instead of typing them again, plug in that phone and run
`bash frida/copy-settings.sh save`, then plug in the target and run
`bash frida/copy-settings.sh restore`. The file is stashed in `work/` (git-ignored) and the keys
are never printed.

## 4. Inject

The launcher is architecture-agnostic: it pushes both `frida-inject` builds and picks the one
matching S Voice's process at attach time.

```bash
bash frida/push-and-run.sh
```

It needs root. The script attaches once and then starts the watchdog: a foreground service in
the bridge APK that holds a root shell loop and reattaches whenever S Voice restarts. Allow the
SuperSU prompt for **S Voice Gemini Bridge** once. The service is registered for boot, for APK
updates and for screen unlocks, so after a reboot or an update it reattaches on its own the next
time S Voice is opened — no PC and no need to open the settings app.

If `frida/bin/` is empty (the binaries are not published here), download Frida
**12.8.20** from <https://github.com/frida/frida/releases/tag/12.8.20>:

* `frida-inject-12.8.20-android-arm.xz` → `frida/bin/frida-inject-12.8.20-android-arm`
* `frida-inject-12.8.20-android-arm64.xz` → `frida/bin/frida-inject-12.8.20-android-arm64`

Decompress the `.xz` files (they are not gzipped tarballs) and keep those exact filenames,
because `push-and-run.sh` looks for them.

On Android 10 the same Frida version still attaches, but Android 11+ needs a newer Frida
release, and the JavaScript API changed between 12.x and 14+/16+. Expect to port `bridge.js`
if you go past Android 10.

## 5. Verify

```bash
adb shell su -c "cat /data/local/tmp/svoice-gemini/bridge.log"
adb logcat -s SVoiceGemini SVoiceGeminiFrida
```

A healthy attach ends with `adopted the running ConversationActivity` and `bridge enabled =
true`. Then tap the microphone and speak: the answer should arrive in S Voice's own bubbles.

## 6. Known limitations on other hardware

* **"Hi Galaxy" wake word** relies on Samsung's `com.samsung.wakeupsetting` service. It is not
  bridged, and on non-Samsung hardware it will not work at all.
* **Device actions** ("call mom", "set an alarm") no longer execute locally — everything goes to
  the model as conversation. This is the same trade-off as on the S6 edge.
* **Target API 19** means no modern background limits are declared by the app. On Android 8+
  keep S Voice in the foreground while talking, otherwise the OS may freeze it mid-recording.
* **Storage** on Android 11+: legacy apps targeting API 19 still read `/sdcard` directly, so
  the settings file keeps working; there is no scoped-storage migration to do.
* **SELinux** may block the write of the optimized dex on stricter ROMs. Run
  `adb shell su -c setenforce 0`, retry, and expect to do this after every reboot.

## 7. Reverting

Uninstall `com.vlingo.midas` and `com.svoice.gemini`. S Voice's APK and odex are never modified,
so a reinstall returns it to stock behaviour.

## Legal

Sideloading an app you dumped from your own Samsung device is a personal-use activity. Do not
upload the APK to a public repository, release, forum post or file host. This repository
deliberately contains none of Samsung's files.
