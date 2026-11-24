# NOTICE

ClawSpecs is a modified version of **Tupo Vision**, https://github.com/KeonyRus/tupo_vision,
Copyright (c) 2026 KeonyRus, released under the MIT License (see `LICENSE`). The fork starts at
upstream commit `8e13554` and keeps the full upstream history.

## Changes from upstream

- `app/src/main/java/com/keonyrus/tupovision/Claw.kt`: new. HTTP client for the ClawSpecs bridge.
- `Engine.kt`: new "agent" mode (default) that sends each question, with a camera frame when it
  is about what the user sees, to the bridge and speaks the answer; spoken filler when the agent
  is slow; Spanish error phrases; wake phrases (comma separated, default `hey claw, oye claw`)
  with sound-alike folding for Spanish speech recognition. The ChatGPT and Gemini Live modes are
  unchanged.
- `MainActivity.kt`: new "Your agent" settings section (address, token, test); wake phrase
  setting now applies to every mode; app name.
- `app/build.gradle.kts`, `AndroidManifest.xml`, `strings.xml`, `.github/workflows/build.yml`:
  application id `io.github.emiliotorrens.clawspecs` so it installs next to Tupo Vision; app and
  artifact names. The Kotlin package stays `com.keonyrus.tupovision` to keep upstream merges easy.
- `bridge/`: new. The bridge server and example agent commands.
- `README.md`: new; the upstream README moved to `docs/UPSTREAM-README.md`.

## Third-party code not included here

- Meta Wearables Device Access Toolkit: downloaded from Maven Central at build time, under
  Meta's license and terms. Not redistributed in this repository.
- No code from Visor (AGPL-3.0) or CJ (MIT) is included at this point.
