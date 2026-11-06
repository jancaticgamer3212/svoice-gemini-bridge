# S Voice → Gemini Bridge

Keeps Samsung S Voice (11.8.0.0/11.9.0.0, `com.vlingo.midas`) exactly as it looks, but replaces
the retired Vlingo cloud recognition with Google Gemini. The APK and ODEX on the device are not
modified.

There are two ways to load the bridge:

* **Frida bridge** (`S-Voice-Gemini-Frida.apk` + `frida/`) — no Xposed, works on rooted 5.0.x and
  5.1.1 including arm64. Use this one: there is no working Xposed for Samsung 5.0.x arm64. The
  APK carries the Frida injectors and the bridge scripts, so it sets itself up on the phone
  without a PC.

* **Xposed module** (`S-Voice-Gemini-Bridge.apk`) — only for a ROM with a compatible Xposed
  framework (for Samsung 5.1.1 arm64, e.g. wanam's builds).

```
microphone tap ──► DialogFlow.startUserFlow(...)  (hooked)
                     │
                     ├─ close S Voice's microphone stream
                     ├─ record 16 kHz mono PCM (VAD, max 15 s)
                     ├─ POST audio + prompt to Gemini (inline audio)
                     ├─ show "You said" + answer bubbles in S Voice
                     └─ speak the answer with Android TTS
```

## Files

| File | Purpose |
| --- | --- |
| `frida/S-Voice-Gemini-Frida.apk` | Bridge APK: settings UI, Gemini client, and the on-phone Frida payload (injectors + scripts) |
| `frida/bridge.js` | Frida script: loads the bridge and hooks `DialogFlow` |
| `frida/push-and-run.sh` | PC script: installs the APK, copies the payload, injects as root, starts the watchdog |
| `frida/device-inject.sh` | On-device one-shot launcher used by `push-and-run.sh` |
| `frida/watchdog.sh` | On-device root loop that reattaches the bridge when S Voice restarts |
| `frida/dump-svoice.sh` | Pulls S Voice from a connected Samsung phone so it can be sideloaded elsewhere |
| `frida/copy-settings.sh` | Moves the API key file between phones (`save` / `restore`) without printing the keys |
| `frida/bin/` | Frida 12.8.20 `frida-inject` builds for arm and arm64 (not in this repo; download the 12.8.20 release) |
| `frida/build.sh` | Rebuild + sign script for the Frida APK (run from `frida/`) |
| `docs/other-devices.md` | Running the bridge on other Samsung/non-Samsung phones |
| `module/S-Voice-Gemini-Bridge.apk` | Signed Xposed module APK (only for Xposed-capable ROMs) |
| `module/` | Xposed module source (`com.svoice.gemini`) |
| `module/build.sh` | Rebuild + sign script for the Xposed APK (run from `module/`) |
| `signing/svoice.keystore` | Local signing key (alias `svoice`) — deliberately not published; back it up outside git |
| `THIRD-PARTY-NOTICES.txt` | Licences for bundled third-party binaries (Frida) |
| `work/`, `apk/`, `tools/` | Local-only folders: decompiled S Voice, extraction tools, build SDK (not in this repo) |

## Requirements

* Any rooted Samsung phone running **S Voice 11.8.0.0 or 11.9.0.0** (`com.vlingo.midas`).
  Verified on SM-G925F (Android 5.0.2, S Voice 11.8.0.0) and SM-A310F (Android 5.1.1,
  S Voice 11.9.0.0). The bridge hooks classes inside S Voice, so it is not tied to a phone
  model; the limits are S Voice's own Samsung dependencies.
* Root (`su`). The Frida APK needs nothing else and can install and attach itself on the phone,
  so no PC or adb is required; the Xposed route additionally needs a compatible framework.
* Xposed only: a framework build that matches your ROM. For a 64-bit Samsung ROM that means
  **Android 5.1.1 (SDK 22, arm64)**, for example wanam's builds. The Samsung 5.0.x community
  builds are **32-bit ARM only**, so on untouched 5.0.2 firmware the module installs and saves
  a key but its hooks cannot run.
* A Gemini API key from Google AI Studio: <https://aistudio.google.com/app/apikey>.
* Network access on the phone.

## Download

Grab `S-Voice-Gemini-Frida.apk` from the
[latest release](https://github.com/jancaticgamer3212/svoice-gemini-bridge/releases/latest).
The Xposed build is only attached to releases when it is rebuilt; otherwise build it from
`module/` (see [Rebuilding](#rebuilding)).

## Frida install from the phone (no PC)

The Frida APK carries the injectors and scripts, so everything below happens on the phone.
This is the recommended route and needs no PC.

1. Install and open `S-Voice-Gemini-Frida.apk`, then allow the `su` request from your root
   manager.
2. In **Bridge control** tap **Install Frida payload (root)**. It copies `bridge.js`,
   `inject.sh`, `watchdog.sh` and the matching 32/64-bit injector into
   `/data/local/tmp/svoice-gemini`; the status then shows `Payload: installed`.
3. Enter the Gemini key, optionally press **Test key**, then **Save**. The status shows
   `API key file: present`.
4. Tap **Attach to S Voice now**. It starts S Voice if needed and attaches the bridge; the
   injector output appears under the buttons, and the status shows `Bridge attached: yes`.
5. Talk to S Voice. The watchdog is already armed by opening this app: it reattaches when S
   Voice restarts, after an APK update and after a reboot.

Tap **Install Frida payload** again after each APK update, because the scripts ship inside the
APK. `su` is used only to copy the payload, read the bridge state and run the injector; no extra
permissions are declared.

## No-root assistant (fallback)

S Voice cannot be hooked without root or Xposed, so the S Voice bridge is root-only. When the
app cannot get root, the **Bridge control** status shows `Root: denied` and an
**Open no-root assistant (fallback)** button appears.

That screen is a small assistant of its own: Android's speech recognizer (or the text field)
feeds Gemini, the answer is shown and spoken with TTS, and calls, text messages, alarms and
timers are carried out through normal Android intents. It never touches S Voice, so there is no
bubble UI and no "Hi Galaxy" wake word. The first install asks for call, SMS, contacts and
microphone permissions for exactly these features; on Android 5.x they are granted at install.

The same four actions work in the rooted S Voice bridge: asking S Voice to call, text, set an
alarm or set a timer returns an `action` object from Gemini or Groq, and the bridge executes it
with S Voice's own permissions before it speaks the confirmation. If an action fails, the reason
is spoken instead of a false success.

## Frida install from a PC

Only needed if you prefer adb. This route needs only root, not Xposed. It uses Frida 12.8.20,
the last series that still supports Android 5.x.

1. Install the bridge APK and open it once:
   ```
   adb install -r S-Voice-Gemini-Frida.apk
   ```
   Paste the Gemini API key, optionally press **Test key**, then **Save**. This writes
   `/sdcard/.svoice_gemini.json`, which the bridge inside S Voice reads on every turn.
2. From this folder on the PC:
   ```
   bash frida/push-and-run.sh
   ```
   The script installs/updates the APK, pushes the payload to `/data/local/tmp/svoice-gemini`,
   starts S Voice if needed, detects whether the process is 32- or 64-bit, and attaches
   `frida/bridge.js` with `--eternalize` so it stays loaded after the injector exits. It also
   refuses to attach twice to the same process.
3. Tap the microphone in S Voice and speak. Check the log with:
   ```
   adb shell su -c "cat /data/local/tmp/svoice-gemini/bridge.log"
   ```
4. `push-and-run.sh` also starts the watchdog: a foreground service in the bridge APK that holds
   a root shell loop. When S Voice exits or is force-stopped, the agent dies with it; the
   watchdog sees the next process and attaches again within a few seconds, without a PC. Allow
   the SuperSU prompt for **S Voice Gemini Bridge** once.

   The watchdog is registered for boot, for APK updates (`MY_PACKAGE_REPLACED`) and for screen
   unlocks, so it starts on its own after a reboot or an update and reattaches when S Voice is
   first opened. If it ever stops, open **S Voice Gemini Bridge** from the app drawer (it
   restarts the service) or broadcast
   `am broadcast -a com.svoice.gemini.START_WATCHDOG -n com.svoice.gemini/.BootReceiver`.
   Watchdog decisions are logged to `/data/local/tmp/svoice-gemini/watchdog.log`.

If injection fails with an SELinux denial, run `adb shell su -c setenforce 0` and retry;
that lasts until the next reboot. The bridge leaves the "Hi Galaxy" wake word untouched and
takes over microphone taps and typed requests.

## Other devices

Nothing in this project is specific to one phone model: `bridge.js` hooks classes that ship
inside the S Voice APK, so the same build of S Voice behaves the same anywhere it runs. The
limits come from S Voice itself (a 2014 TouchWiz app that expects Samsung system services). See
[`docs/other-devices.md`](docs/other-devices.md) for the compatibility matrix, how to dump S
Voice from a device you own, and where the wake word and Samsung integrations stop working.

The S Voice APK is **not** distributed here — it is Samsung's software and also contains
Samsung service credentials. Dump your own copy for personal use.

## Xposed install

> **5.0.2 note.** Check the firmware first with
> `adb shell getprop ro.product.cpu.abilist` and `adb shell getprop ro.build.version.release`.
> If it reports `arm64-v8a` and `5.0.2`, stop at step 2 below: the module installs and can save
> and test an API key, but no supported Xposed framework exists for that combination. The
> framework route requires updating to 5.1.1 first (or using Frida instead of Xposed).

1. Flash the Samsung-compatible Xposed framework zip in TWRP, reboot, install the matching
   Xposed Installer APK.
2. Install the module:
   ```
   adb install -r S-Voice-Gemini-Bridge.apk
   ```
   or copy the APK to the phone and open it.
3. In Xposed Installer → **Modules**, enable **S Voice Gemini Bridge**. Make sure its scope
   includes **S Voice** (`com.vlingo.midas`), then reboot.
4. Open **S Voice Gemini Bridge** from the app drawer, paste the API key, optionally press
   **Test key**, then **Save**.
5. Open S Voice and tap the microphone. Speak, then stop talking; the answer appears after
   roughly a second of silence.

The key is re-read on every turn, so changing it does not require a reboot or a restart of S Voice.

## What the module hooks

| Target | Why |
| --- | --- |
| `ConversationActivity.onCreate/onResume` | Keep a reference to the visible activity for bubbles and state changes |
| `DialogFlow.startUserFlow(RecognitionMode, WakeUpContext, IAudioStream)` | Intercept microphone turns, close the app's stream, run the Gemini turn |
| `DialogFlow.startUserFlow(String, Object)` | Route typed/quick-reply text to Gemini as well |

UI is driven through the app's own classes: `ControlFragment.setState(ASR_LISTENING /
ASR_THINKING / IDLE)`, `DialogFragment.addDialogBubble(USER / VLINGO / ERROR, …)`,
`ConversationActivity.onASRRecorderClosed()`, and the enums `DialogBubble$BubbleType` and
`ControlFragment$ControlState`. No layout or resource of S Voice is changed.

## Behaviour and limitations

* **All** requests go to Gemini, including phrases S Voice would normally execute locally
  ("call mom", "set an alarm"). The assistant now performs calls, texts, alarms and timers by
  returning an action the bridge executes; other device features still become conversational
  answers, because interception happens before S Voice's own command handling.
* The **"Hi Galaxy" wake word is not bridged**. It uses Samsung's own wake-up recorder; the
  module deliberately leaves that path untouched instead of fighting it for the microphone.
  Tap the mic instead.
* Answers are shown in S Voice's normal bubbles and spoken with the phone's default Android
  TTS engine. Turn off "Speak answers" if you only want text; if no TTS engine is installed,
  answers are text-only automatically.
* S Voice 11.8.0.0 and 11.9.0.0 are supported and both were verified on real phones. Method
  names and fields differ between releases, so any other S Voice version needs the hook targets
  re-checked against `work/smali`.
* TLS 1.2 is forced and the module pins the Google Trust Services roots (GTS R1/R4), because
  Android 5.0's CA store predates them. If Google changes the certificate chain, the module
  must be rebuilt with the new root.
* The device clock must be reasonably correct or certificate validation fails.
* `gemini-3.6-flash` is the default model; any model name available to your key can be typed
  into Settings. Gemini 2.5 models are rejected for newly created keys ("no longer available
  to new users", HTTP 404), and Google's suggested `gemini-3.8-flash` can return HTTP 503 while
  it is capacity-limited. If a model fails, open Settings and type one from
  `GET https://generativelanguage.googleapis.com/v1beta/models` that works with your key.
  HTTP 503/500/429 ("high demand") is handled automatically: the request is retried up to three
  times with a short backoff, then repeated on `gemini-3.5-flash` (the built-in fallback), so a
  busy model usually still answers without any change on the phone.
  For 2.5 Flash the module disables thinking tokens (budget 0) so the whole output budget goes
  to the spoken answer; if the API rejects that field the request is retried once without it.
* **Groq fallback (optional, free).** Paste a Groq key in Settings (`gsk_…`) and optionally a
  model name (default `openai/gpt-oss-120b`). If the Gemini key is empty, or a Gemini turn still
  fails after its retries, the turn is answered by Groq instead: audio is transcribed with
  `whisper-large-v3-turbo` (falling back to `whisper-large-v3`), then the transcript goes through
  Groq's OpenAI-compatible `/chat/completions` with the same personality prompt. Because Groq
  retires models often, the client walks a chain — configured model, then `openai/gpt-oss-120b`,
  `llama-3.3-70b-versatile`, `llama-3.1-8b-instant` — and moves on when one is missing.
  Groq's 429/5xx responses are retried once with a 2 s backoff, and `max_tokens`/`temperature`
  are dropped if the model rejects them. The log shows which provider answered
  (`answered by Gemini` / `answered by Groq` under the `SVoiceGemini` tag). Free-tier limits
  (requests per minute and per day) apply.

## API key storage (read this)

The key is **not** compiled into the APK. It is stored in two plain-text places on the phone:

1. the app's private `SharedPreferences` (made world-readable so the code inside S Voice's
   process can read it; used by the Xposed build), and
2. `/sdcard/.svoice_gemini.json` (read by both builds; the Frida build uses only this file).
   The Gemini key is `apiKey`; the optional Groq key is `groqKey`.

Any app with storage access on this old Android version can read that file. Use a key that is
restricted to the **Generative Language API** in Google Cloud, set quotas/billing alerts, and
revoke the key if the phone is lost. Audio from each turn is sent to Google Gemini.

## Rebuilding

The toolchain (SDK platform 21, build-tools 34, Xposed API 82) is under `tools/`:

```
bash module/build.sh   # Xposed module APK   -> module/S-Voice-Gemini-Bridge.apk
bash frida/build.sh    # Frida bridge APK    -> frida/S-Voice-Gemini-Frida.apk
```

`frida/build.sh` copies `frida/bin/frida-inject-12.8.20-android-*` (download the 12.8.20 release
from Frida and put them there) into the APK as `lib/<abi>/libfrida_inject.so`, together with
`bridge.js` and the two shell scripts.

The scripts compile resources with `aapt2`, compile with `javac` against `android.jar`,
dex with `d8`, package, zipalign and sign with `signing/svoice.keystore`. Keep that
keystore if you want to install updates over the existing app; a different key requires
uninstalling the app first. Both APKs use the package `com.svoice.gemini`, so installing one
replaces the other.

`tools/` (the SDK and the Xposed API jar) and `signing/svoice.keystore` are **not** in this
repository. Put your own SDK at `tools/sdk` with `build-tools/34.0.0` and
`platforms/android-21`, drop `xposed-api-82.jar` into `tools/lib`, and either add your keystore
at `signing/svoice.keystore` or let the script generate one (that changes the app signature,
so uninstall first). Building from source therefore produces an APK that cannot update the
published release build.

## Troubleshooting

* **Frida log says `bridge load failed`** — the bridge APK is not installed for the same user,
  or SELinux blocked writing the optimized dex. Reinstall the APK, then check
  `adb shell su -c "cat /data/local/tmp/svoice-gemini/bridge.log"`.
* **Frida log says `DialogFlow not available`** — that S Voice build has different class names.
  The hooks were verified on 11.8.0.0; a different version needs `frida/bridge.js` adjusted.
* **Injection works but nothing changes** — the settings file is missing or has no key. Open
  **S Voice Gemini Bridge**, enter the key, tap **Save**; the log then prints
  `ready; bridge enabled = true`.
* **Nothing changed after tapping the mic (Xposed build)** — confirm the module is enabled in
  Xposed Installer and that S Voice is in its scope, then reboot. Check logs with
  `adb logcat | grep -i svoicegemini`; the module also writes `S Voice Gemini: hooks installed`
  to the Xposed log.
* **"Gemini error 400/403"** — bad or restricted key, or a model name your key cannot use.
  Press **Test key** in the module settings.
* **"I didn't catch that"** — the VAD did not detect speech (too quiet, or the mic is held by
  another app). Raise the speaking volume or close other voice apps.
* **Bootloop** — boot into TWRP and flash the Xposed uninstaller zip for the framework version
  you installed, then reboot. S Voice itself is never modified, so removing Xposed fully
  restores the stock behaviour.

## Status

Both APKs build and pass `apksigner verify`, and every hook target was checked against the
decompiled odex. The Frida build has been run on two phones — SM-G925F (Android 5.0.2, S Voice
11.8.0.0) and SM-A310F (Android 5.1.1, S Voice 11.9.0.0) — so it is not model-specific: the hooks
install, the running `ConversationActivity` is adopted, and the watchdog reattaches after S Voice
is force-stopped and after an APK update. The on-phone payload installer and attach button run
the same code that was tested as a standalone launcher app. Recognition itself depends on your
key and network, so confirm the first spoken turn in `bridge.log`.

## Licences

This project's own code is free to use and modify. `S-Voice-Gemini-Frida.apk` bundles
unmodified Frida 12.8.20 `frida-inject` binaries, published under the wxWindows Library Licence
3.1; the full text is in [`THIRD-PARTY-NOTICES.txt`](THIRD-PARTY-NOTICES.txt). Samsung's S Voice
is not part of this project or its releases.
