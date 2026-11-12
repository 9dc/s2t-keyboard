# S2T Mic

S2T Mic adds a draggable, one-tap speech-to-text button above Gboard and other
Android keyboards. It uses an Accessibility Service to detect editable fields
and insert the final transcript at the cursor without replacing the keyboard.

## Features

- Small button sits over the keyboard's top-right microphone area and appears
  only while a software keyboard and editable field are active
- Draggable position follows the keyboard's top edge, is saved separately for
  portrait and landscape and can be locked
- Recording starts immediately and stays local until stopped
- Multilingual transcription with a choice of providers: Groq Whisper
  Large V3 Turbo or OpenRouter (pick from the live list of STT models);
  the spoken language can be pinned for better accuracy on short dictations
- Per-provider API keys, each encrypted with Android Keystore
- Tap once to start and again to stop and insert the text; long-press to
  discard a recording or a running transcription
- On Android 13+ the text is typed through the field's input connection like
  a keyboard would; older versions rewrite the field's text
- A transcript that cannot be inserted is copied to the clipboard, and a
  failed upload can be sent again with one tap
- 16 kHz mono PCM audio with automatic resampling, up to 5 minutes per dictation
- No credential or audio logging; password fields are excluded

## Install and configure

Download the latest APK from [GitHub Releases](https://github.com/9dc/s2t-keyboard/releases/latest),
install it, then:

1. Open **S2T Mic** and grant microphone access.
2. Pick a transcription provider and enter its API key. For OpenRouter the
   app loads the live list of speech-to-text models and lets you choose one.
3. Open Accessibility settings and enable **S2T Mic Eingabehilfe**. If Android
   13+ greys the switch out for a sideloaded APK, open the app's info page and
   choose **Allow restricted settings** from the ⋮ menu first.
4. Focus a text field and open your keyboard.
5. Tap the floating microphone, speak, then tap the stop button.

Some Android vendors may require background execution to be allowed in their
battery settings.

### Obtainium

Add this repository URL to Obtainium:

```text
https://github.com/9dc/s2t-keyboard
```

Tagged releases contain a signed APK with an increasing Android version code.

## Build

Requirements: JDK 17 and Android SDK 35.

```bash
./gradlew test assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tags matching `v*` trigger the GitHub Actions release workflow. Release signing
uses these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep the release keystore backed up. Android cannot install future updates over
an existing installation if they are signed with a different key.

## Notes

- The app records PCM audio in memory, wraps it as WAV, and uploads it to the
  selected provider only after you tap stop.
- Some hardened apps and custom WebViews may reject Accessibility text insertion.
- The API key is encrypted at rest but necessarily exists briefly in app memory.
  For distribution to other users, prefer short-lived tokens issued by a backend.
- Usage is billed to the supplied provider key (Groq or OpenRouter).

Provider documentation:

- Groq [speech-to-text](https://console.groq.com/docs/speech-to-text)
- OpenRouter [speech-to-text](https://openrouter.ai/docs/api/api-reference/stt/create-transcription)
  and the [MAI-Transcribe 2](https://openrouter.ai/microsoft/mai-transcribe-2) model
