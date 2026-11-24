# NOTICE

MetaClaw is a modified version of **Tupo Vision**, https://github.com/KeonyRus/tupo_vision,
Copyright (c) 2026 KeonyRus, released under the MIT License (see `LICENSE`). The fork starts at
upstream commit `8e13554` and keeps the full upstream history.

## Changes from upstream

- `app/src/main/java/com/keonyrus/tupovision/Claw.kt`: new. HTTP client for the MetaClaw bridge.
- `Engine.kt`: new "agent" mode (default) that sends each question, with a camera frame when it
  is about what the user sees, to the bridge and speaks the answer; spoken filler when the agent
  is slow; Spanish error phrases; wake phrases (comma separated, default `hey claw, oye claw`)
  with sound-alike folding for Spanish speech recognition. The ChatGPT and Gemini Live modes are
  unchanged.
- `MainActivity.kt`: new "Your agent" settings section (address, token, test); wake phrase
  setting now applies to every mode; app name.
- `app/build.gradle.kts`, `AndroidManifest.xml`, `strings.xml`, `.github/workflows/build.yml`:
  application id `io.github.emiliotorrens.metaclaw` so it installs next to Tupo Vision; app and
  artifact names. The Kotlin package stays `com.keonyrus.tupovision` to keep upstream merges easy.
- `bridge/`: new. The bridge server and example agent commands.
- `README.md`: new; the upstream README moved to `docs/UPSTREAM-README.md`.
- `Hotword.kt`, `WakeRule.kt`, `tools/wake_test.py`, `scripts/get-wake-model.sh`: new. Offline
  wake phrase with Vosk and its small Spanish model (see below for what comes from CJ).
- `Engine.kt`, `MainActivity.kt`: "Offline wake phrase" switch (on by default when the model is
  in the build); after the wake phrase a beep, then speech recognition takes the question.
- `app/build.gradle.kts`: Vosk and JNA dependencies, unit tests, ABIs limited to arm64-v8a and
  x86_64.

## Third-party code not included here

- Meta Wearables Device Access Toolkit: downloaded from Maven Central at build time, under
  Meta's license and terms. Not redistributed in this repository.
- Vosk small Spanish model `vosk-model-small-es-0.42` by Alpha Cephei, Apache License 2.0
  (https://alphacephei.com/vosk/models). Downloaded by `scripts/get-wake-model.sh` at build time
  and bundled in the APK unchanged, with its own README; not stored in this repository.
- Vosk Android library (`com.alphacephei:vosk-android`, Apache 2.0) and JNA (Apache 2.0 / LGPL
  2.1 dual licensed, used under Apache 2.0): Maven dependencies.
- No code from Visor (AGPL-3.0) is included.

## Code adapted from CJ (MIT)

`Hotword.kt`, `WakeRule.kt`, `tools/wake_test.py` and `scripts/get-wake-model.sh` are adapted
from CJ / rayban-meta-god-mode, https://github.com/GhanshyamPaunikar/rayban-meta-god-mode
(commit `eb1eaa6`): `Hotword.kt`, `tools/wake_test.py` and `scripts/get-wake-model.sh` there.
Taken: the approach (a restricted Vosk grammar with decoy words, a rule on where the name may
appear, a confidence check on settled results, a synthetic-speech test) and the structure of the
model loading and recognition loop. Changed: Spanish model, words, rule and thresholds; MetaClaw
opens its own microphone stream; the test uses edge-tts voices instead of macOS `say`.

Copyright (c) 2026 Ghanshyam Paunikar. Used under the MIT License; the full notice is in
[`licenses/CJ-MIT-LICENSE`](licenses/CJ-MIT-LICENSE).
