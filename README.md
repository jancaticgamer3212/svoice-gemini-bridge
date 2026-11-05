# S Voice → Gemini Bridge

Keeps Samsung S Voice (11.8.0.0/11.9.0.0, `com.vlingo.midas`) exactly as it looks, but replaces
the retired Vlingo cloud recognition with Google Gemini. The APK and ODEX on the device are not
modified.

There are two builds:

* **Frida bridge** (`S-Voice-Gemini-Frida.apk` + `frida/`) — no Xposed, works on rooted 5.0.x and
  5.1.1 including arm64. Use this one: there is no working Xposed for Samsung 5.0.x arm64.

* **Xposed module** (`S-Voice-Gemini-Bridge.apk`) — only for a ROM with a compatible Xposed
  framework (S6/S6 edge: 5.1.1 SDK 22 arm64, e.g. wanam's builds).

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
| `S-Voice-Gemini-Frida.apk` | Bridge APK (settings UI + Gemini client), loaded into S Voice by Frida |
| `frida/bridge.js` | Frida script: loads the bridge and hooks `DialogFlow` |
| `frida/push-and-run.sh` | PC script: installs the APK, copies the payload, injects as root, starts the watchdog |
| `frida/device-inject.sh` | On-device one-shot launcher used by `push-and-run.sh` |
| `frida/watchdog.sh` | On-device root loop that reattaches the bridge when S Voice restarts |
| `frida/dump-svoice.sh` | Pulls S Voice from a connected Samsung phone so it can be sideloaded elsewhere |
| `frida/bin/` | Frida 12.8.20 `frida-inject` builds for arm and arm64 (not in this repo; download the 12.8.20 release) |
| `build-frida.sh` | Rebuild + sign script for the Frida APK |
| `docs/other-devices.md` | Running the bridge on other Samsung/non-Samsung phones |
| `S-Voice-Gemini-Bridge.apk` | Signed Xposed module APK (only for Xposed-capable ROMs) |
| `module/` | Xposed module source (`com.svoice.gemini`) |
| `build.sh` | Rebuild + sign script for the Xposed APK |
| `signing/svoice.keystore` | Local signing key (alias `svoice`) — deliberately not published; back it up outside git |
| `work/`, `apk/`, `tools/` | Local-only folders: decompiled S Voice, extraction tools, build SDK (not in this repo) |

## Requirements

* Galaxy S6 edge (SM-G925F), rooted, with TWRP.
* An Xposed framework build that works on **this** Samsung TouchWiz ROM. Important: the
  Samsung 5.0.x community builds are **32-bit ARM only**; the S6 edge firmware is 64-bit, and
  no Samsung 5.0.2 arm64 build exists. On untouched 5.0.2 firmware this module can be
  installed and its settings screen used, but the hooks cannot run. Xposed for the S6/S6 edge
  does exist for **Android 5.1.1 (SDK 22, arm64)** — for example wanam's builds, which work on
  stock odexed or deodexed ROMs.
* A Gemini API key from Google AI Studio: <https://aistudio.google.com/app/apikey>.
* Network access on the phone.

## Frida install (recommended, no Xposed)

This route needs only root, not Xposed. It uses Frida 12.8.20, the last series that still
supports Android 5.x.

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

   The watchdog is registered for boot, so after a reboot it starts on its own and reattaches
   when S Voice is first opened. If it ever stops, open **S Voice Gemini Bridge** from the app
   drawer (it restarts the service) or broadcast
   `am broadcast -a com.svoice.gemini.START_WATCHDOG -n com.svoice.gemini/.BootReceiver`.
   Watchdog decisions are logged to `/data/local/tmp/svoice-gemini/watchdog.log`.

If injection fails with an SELinux denial, run `adb shell su -c setenforce 0` and retry;
that lasts until the next reboot. The bridge leaves the "Hi Galaxy" wake word untouched and
takes over microphone taps and typed requests.

## Other devices

Nothing in this project is specific to the S6 edge: `bridge.js` hooks classes that ship inside
the S Voice APK, so the same build of S Voice behaves the same anywhere it runs. The limits come
from S Voice itself (a 2014 TouchWiz app that expects Samsung system services). See
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
  ("call mom", "set an alarm"). Gemini cannot perform device actions, so those become
  conversational answers. This is the trade-off of intercepting before recognition.
* The **"Hi Galaxy" wake word is not bridged**. It uses Samsung's own wake-up recorder; the
  module deliberately leaves that path untouched instead of fighting it for the microphone.
  Tap the mic instead.
* Answers are shown in S Voice's normal bubbles and spoken with the phone's default Android
  TTS engine. Turn off "Speak answers" if you only want text; if no TTS engine is installed,
  answers are text-only automatically.
* Only this S Voice build (11.8.0.0) is targeted. Method names and fields were verified in
  `work/smali`; a different S Voice version will need those names re-checked.
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
bash build.sh        # Xposed module APK
bash build-frida.sh  # Frida bridge APK
```

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
decompiled odex. The Frida build has been run on an SM-G925F (Android 5.0.2): the hooks install,
the running `ConversationActivity` is adopted, and the watchdog reattaches after S Voice is
force-stopped. Recognition itself depends on your key and network, so confirm the first spoken
turn in `bridge.log`.
