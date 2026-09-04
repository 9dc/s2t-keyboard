# S2T Mic – Gboard Companion (MVP)

Eine kleine Android-App, die **Gboard ergänzt und nicht ersetzt**. Sobald ein editierbares Textfeld fokussiert ist, zeigt ein Accessibility Service einen schwebenden Mikrofon-Button. Ein Tipp startet OpenAI Realtime-Transkription, ein zweiter Tipp beendet das Diktat und setzt den finalen Text an der Cursorposition ein.

## Stand der API-Prüfung (4. September 2026)

Der MVP verbindet sich mit einer Realtime-Session über
`wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1` und verwendet darin
bewusst `gpt-live-transcribe` als Transkriptionsmodell.

- Die [offizielle OpenAI-Anleitung zur Realtime-Transkription](https://developers.openai.com/api/docs/guides/realtime-transcription) empfiehlt `gpt-live-transcribe` für Live-Audio und liefert `conversation.item.input_audio_transcription.delta` sowie `...completed`.
- `gpt-transcribe` ist laut derselben Anleitung für **committete** Audioturns gedacht; die Transkription beginnt dort erst nach dem Commit. Das erfüllt die gewünschte Live-Anzeige schlechter.
- Der [offizielle Modell-Eintrag](https://developers.openai.com/api/docs/models/gpt-live-transcribe) führt Realtime-Streaming und den Realtime-Endpoint auf.
- OpenRouter ist in diesem reduzierten MVP nicht enthalten. Dessen dokumentierter STT-Pfad ist derzeit ein einzelner HTTP-Upload, kein gleichwertiger Realtime-WebSocket mit Deltas.

## Enthalten

- Kotlin + Jetpack Compose für Einrichtung und Status
- `TYPE_ACCESSIBILITY_OVERLAY`, daher keine Berechtigung „Über anderen Apps einblenden“
- 24-kHz-Mono-PCM16-Aufnahme; Fallback-Resampling für Geräte ohne native 24 kHz
- Live-Partial-Text direkt neben dem Mic-Button
- Finales Einfügen über `AccessibilityNodeInfo.ACTION_SET_TEXT` und Wiederherstellung der Cursorposition
- Deutsch/Englisch als gleichzeitige Language-Hints; Sprachwechsel werden unterstützt
- API-Key verschlüsselt mit AES-GCM und einem nicht exportierbaren Android-Keystore-Key
- Keine Backups der Credential-Preferences, keine Key-/Audio-Logs
- Passwortfelder werden ausgeschlossen
- Event-basierter Service: Mikrofon, Netzwerk und Rechenarbeit laufen nur während eines Diktats

## Bauen und installieren

### Android Studio

1. Installiere eine aktuelle Android-Studio-Version mit JDK 17 und Android SDK 35.
2. Öffne diesen Ordner als Projekt und warte auf den Gradle-Sync.
3. Aktiviere am Oppo **Entwickleroptionen → USB-Debugging** und verbinde es per USB.
4. Wähle das Gerät in Android Studio und starte die Konfiguration `app`.

### Kommandozeile

Mit gesetztem `ANDROID_HOME`/`ANDROID_SDK_ROOT`, JDK 17 und einem verbundenen Gerät:

```bash
./gradlew test assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Releases und Obtainium

Git-Tags im Format `v*` starten den GitHub-Actions-Workflow. Er testet die App,
erstellt eine signierte Release-APK und hängt sie an ein GitHub Release. Die
laufende Workflow-Nummer wird als Android-`versionCode` verwendet, damit
Obtainium Updates erkennt und installieren kann.

Im GitHub-Repository müssen diese Actions-Secrets hinterlegt sein:

- `ANDROID_KEYSTORE_BASE64`: Base64-kodierte Release-Keystore-Datei
- `ANDROID_KEYSTORE_PASSWORD`: Passwort des Keystores
- `ANDROID_KEY_ALIAS`: Alias des Signierschlüssels
- `ANDROID_KEY_PASSWORD`: Passwort des Signierschlüssels

Danach lässt sich das Repository in Obtainium über seine GitHub-URL hinzufügen.
Ein Release wird beispielsweise so ausgelöst:

```bash
git tag v0.1.0
git push origin main v0.1.0
```

Der Signierschlüssel und seine Passwörter müssen dauerhaft gesichert werden.
Ohne denselben Schlüssel kann Android keine Updates über eine bestehende
Installation installieren.

## Auf dem Oppo einrichten und testen

1. Öffne **S2T Mic**.
2. Erlaube das Mikrofon.
3. Trage einen OpenAI API-Key mit verfügbarem API-Guthaben ein und tippe **Key speichern**.
4. Öffne über die App die Eingabehilfen und aktiviere **S2T Mic Eingabehilfe**. Android/ColorOS zeigt dabei eine weitreichende Zugriffswarnung, weil der Service Textfelder lesen und bearbeiten können muss.
5. Lass Gboard als Standardtastatur eingestellt.
6. Öffne zuerst eine harmlose Notiz oder einen Chatentwurf, fokussiere das Textfeld und warte auf Gboard. Der lila Mic-Button erscheint unten rechts und kann gezogen werden.
7. Tippe den Button, sprich Deutsch oder Englisch und beobachte den Partial-Text. Tippe das rote Quadrat zum Beenden. Der finale Text wird an der Cursorposition eingefügt.
8. Wiederhole den Test in WhatsApp, ChatGPT, Gmail und einem normalen Browser-Textfeld.

Falls ColorOS den Dienst nach längerer Zeit beendet, erlaube für S2T Mic unter Akku-/App-Verwaltung die Hintergrundausführung. Der MVP fordert absichtlich keinen dauerhaften Vordergrunddienst an.

## Bekannte MVP-Grenzen

- Manche sicherheitsgehärteten Apps oder spezielle WebViews verweigern `ACTION_SET_TEXT`. Normale native Editoren und übliche Browserfelder unterstützen es in der Regel.
- Der API-Key ist verschlüsselt auf dem Gerät, wird für den WebSocket aber naturgemäß kurz im App-Speicher benötigt. Für eine veröffentlichte oder an Dritte verteilte App sollte ein eigener Backend-Token-Service statt eines langlebigen Keys im Client verwendet werden.
- Die Erkennung der sichtbaren Bildschirmtastatur verwendet deren Accessibility-Fenster. Stark angepasste Android-Versionen können diese Information verzögert melden.
- Echte Gerätetests benötigen einen abrechenbaren OpenAI-Key und können nicht durch Unit-Tests ersetzt werden.

## Projektstruktur

- `MainActivity.kt`: Compose-Einrichtung
- `DictationAccessibilityService.kt`: Feld-Erkennung und Einfügen
- `DictationOverlayView.kt`: verschiebbarer Mic-/Partial-Overlay
- `OpenAiRealtimeTranscriber.kt`: aktuelles Realtime-WebSocket-Protokoll
- `PcmAudioRecorder.kt`: Mikrofon und 24-kHz-PCM
- `ApiKeyStore.kt`: Android-Keystore-Verschlüsselung
