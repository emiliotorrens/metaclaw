# Tupo Vision

**Your own AI assistant in Ray-Ban Meta glasses.** An open-source Android app that lets the
glasses talk to ChatGPT or Gemini instead of Meta AI, and lets the model see through the glasses
camera.

- **GPT on your ChatGPT plan, no API key.** Sign in with ChatGPT and requests count against the
  Go, Plus or Pro plan you already pay for.
- **Live voice with Gemini Live.** The model hears and speaks by itself, you can interrupt it,
  and it watches the camera continuously. Needs a Gemini API key.
- **Sees what you see.** Frames come from the glasses camera, not the phone.
- **Hands stay free.** The phone can sit in a pocket with the screen off. A tap on the temple
  pauses and resumes.

Built and tested on Ray-Ban Meta Gen 1. Meta's Wearables Device Access Toolkit, which the app is built
on, also covers Ray-Ban Meta Gen 2, Oakley Meta and Meta Ray-Ban Display; those are untested
here. Not affiliated with Meta, OpenAI or Google.

> **Status: a personal project, no support.** I made this for myself: one pair of Ray-Ban Meta
> Gen 1 and one Android phone. It works there; expect rough edges elsewhere. All the code was
> written by Claude Fable 5.2 (Anthropic); I said what I wanted and tested it on my glasses. I do
> not plan to maintain it and may not answer issues. It is MIT licensed, so fork it and do what
> you want.

## What you need

- Ray-Ban Meta glasses, paired with the **Meta AI** app on your phone
- An Android phone with Android 12 or newer
- One of:
  - a ChatGPT **Go, Plus or Pro** plan, or
  - a **Gemini API key** from [aistudio.google.com](https://aistudio.google.com) (takes a minute)

## Install

1. **Download** `tupo-vision.apk` from the [latest release](../../releases/latest) on your phone
   and open it. Android will ask to allow installing from your browser: allow it.
2. **Turn on Developer Mode in Meta AI.** Put the glasses on so they are connected, then in the
   Meta AI app open **Settings → App info**, tap **App version** five times, switch
   **Developer Mode** on and tap **Enable**. On the same screen, tap **Install** next to
   **Device Access Toolkit** if it is not installed.
3. **Open Tupo Vision** and tap **Grant permissions**.
4. Tap **Link glasses through Meta AI** and confirm in Meta AI.
5. **Pick an assistant:**
   - *ChatGPT plan:* tap **Continue with ChatGPT**, sign in, and allow the app to use your plan.
   - *Gemini Live:* switch on **Live voice**, paste your Gemini API key, tap **Save key**.
6. Tap **Start conversation** and talk. The first time, Meta AI asks for access to the glasses
   camera.

That is all. Next time, only step 6.

## Using it

- **Pause and resume:** one tap on the temple of the glasses.
- **Stop:** the Stop button in the app.
- **Standby (experimental):** start and stop from the glasses. In Meta AI, assign **Tap and hold**
  to **Music** (Spotify is the default here). Switch on **Standby** in Tupo Vision and grant
  notification access. A tap and hold then makes the glasses start the music app; if the phone is
  locked or its screen is off, Tupo Vision pauses the music and toggles the conversation. Music
  you start with the phone in your hand is left alone, and so is music that plays through a car
  or other headphones: switch Standby on with the glasses on, and it follows that audio device
  only. Android gives an app the name of the
  playing music app only with notification access; Tupo Vision reads no notifications.
- **ChatGPT mode** listens for a phrase, sends it with one camera frame, and reads the answer
  aloud. Set your **Speech language** in Settings (for example `en-US` or `ru-RU`). An optional
  wake word makes it ignore everything that does not contain that word.
- **Live voice mode** is a continuous conversation. The model picks up your language by itself.
  If it keeps cutting itself off, turn off **Interrupt by voice** in Settings.

## Which mode to pick

| | ChatGPT plan | Gemini Live |
|---|---|---|
| Needs | Go, Plus or Pro plan | Gemini API key |
| Feels like | Ask, wait a moment, hear the answer | A live conversation |
| Voice | Your phone's text-to-speech | The model's own voice |
| Interrupting | No | Yes |
| Camera | One frame per question | About one frame a second |
| Cost | Included in your plan's limits | Google's free tier or per-minute rates |

The ChatGPT sign-in does not accept audio, which is why that mode recognises and synthesises
speech on the phone. The real ChatGPT voice mode is not available to other apps on a plan.

## Troubleshooting

- **Meta AI says “Error while opening link”.** Developer Mode is not on. Switch it off and on
  again in Meta AI (step 2), force-stop and reopen Meta AI, then link again.
- **ChatGPT sign-in fails with a network error.** Some phones cut background apps off the
  network. Tap **Continue with ChatGPT** again. If it repeats, turn off data saver and battery
  saver for the sign-in, and check that a VPN or DNS filter is not blocking the app.
- **The model cannot see.** Check the line under the Start button: it shows the stream state and
  the last frame. The log says when no video arrives.
- **Answers are quiet.** With the glasses microphone on, the volume follows the phone's call
  volume.
- **Taps on the glasses no longer control music.** Seen on Ray-Ban Meta Gen 1: while Tupo Vision
  is open, play/pause and skip taps on the temple do nothing. Close the app (swipe it away from
  recent apps) and they work again.
- **Anything else:** scroll down and tap **Copy log**; the log usually says what went wrong. You
  can attach it to an issue, but see the status note at the top: there may be no answer.

## Privacy and security

- The app has no server and no analytics of its own. Audio, frames and text go straight from your
  phone to the assistant you picked: OpenAI in ChatGPT mode, Google in Gemini Live mode. In
  ChatGPT mode, speech is recognised and synthesised by your phone's own speech services.
- ChatGPT tokens and the Gemini key are stored on the phone only, encrypted with a key held in
  the Android Keystore. They are never written to the log.
- Meta's SDK is asked not to send its own analytics and crash reports from this app.
- The camera in your glasses records people around you. Follow the law where you are and tell
  people when the assistant is on.

### Checking a download

Each release note states the SHA-256 of the APK and which key signed it. Builds signed with the
project key carry this certificate fingerprint:

```
EF:D9:96:B9:3B:08:81:72:AE:8F:F5:14:37:F9:60:FF:DA:E5:51:97:B2:B9:78:47:2C:25:DA:BA:F3:0E:42:AE
```

Check it with `apksigner verify --print-certs tupo-vision.apk`. Install the app only from this
repository's releases.

## How it works

```
ChatGPT mode
  glasses mic → speech recognition on the phone → question + camera frame
      → OpenAI Responses API on your ChatGPT plan → text-to-speech → glasses speakers

Gemini Live mode
  glasses mic + one camera frame a second → Gemini Live API over one WebSocket
      → the model's voice → glasses speakers
```

- Camera and session: [Meta Wearables Device Access Toolkit](https://github.com/facebook/meta-wearables-dat-android)
- ChatGPT plan: [Sign in with ChatGPT](https://developers.openai.com/siwc) for open-source apps
- Live voice: [Gemini Live API](https://ai.google.dev/gemini-api/docs/live)

## Limitations

- The app runs in Meta's Developer Mode. Publishing it to a store would need registration in
  Meta's Wearables Developer Center.
- Starting a conversation needs the phone. Pausing and resuming does not. Starting by a tap
  gesture was tried and does not work: the glasses stop forwarding taps to the phone while the
  app is open.
- Sign in with ChatGPT for open-source apps is a preview, and its terms can change.
- Glasses microphones reach the phone at phone-call quality over Bluetooth.

## Build it yourself

```
./gradlew assembleDebug
```

Needs JDK 17 and Android SDK 36. GitHub Actions builds every push to `main` and publishes the
APK as the `latest` release.

The published APK is signed with a private key kept in the repository's Actions secrets
(`SIGNING_KEYSTORE_B64`, `SIGNING_KEYSTORE_PASSWORD`). A fork has no such secrets, so its builds
are signed with a throwaway key and cannot be installed over the published app. To give a fork a
stable key of its own, create a PKCS#12 keystore with the alias `tupovision` and add those two
secrets.

## Similar projects

Other open-source ways to put your own assistant on these glasses:

- [VisionClaw](https://github.com/Intent-Lab/VisionClaw): Gemini Live with agent actions, iOS and Android
- [MetaLens AI](https://github.com/przemek-nowicki/meta-lens-ai): ChatGPT, Gemini and Grok with your API keys, Android
- [OpenVision](https://github.com/rayl15/OpenVision): several cloud and on-device models, iOS

## License

MIT for the code in this repository. Meta's SDK is a dependency and is covered by the Meta
Wearables Developer Terms.

---

**По-русски.** Tupo Vision ставит в очки Ray-Ban Meta своего ассистента: GPT по подписке ChatGPT
без API-ключа или живой голос Gemini Live. Установка: скачай APK из релиза, включи Developer Mode
в Meta AI (Settings → App info, пять тапов по App version), выдай разрешения, привяжи очки,
выбери ассистента и нажми Start conversation.
