# Plan

## v0.1 (this version)

- [x] Fork Tupo Vision, own application id, builds locally.
- [x] Agent mode: question → bridge → agent → spoken answer.
- [x] Wake phrases "hey Claw" / "oye Claw", with Spanish sound-alikes.
- [x] Camera frame only for visual questions (or always in photo mode).
- [x] Spoken filler after 4 s without an answer.
- [x] Bridge: token auth, one run per conversation, no content in logs, temp frames deleted.
- [ ] First run on real glasses (Ray-Ban Meta, Pixel).
- [ ] Measure latency end to end and tune the filler delay.

## Next

- **Offline hotword** with Vosk (idea from CJ, MIT). Today the wake phrase rides on Android's
  continuous speech recognition, which uses more battery and may send audio to Google's
  recognizer depending on the phone. A small offline model only wakes recognition after "Claw".
- **Streaming answers**: bridge returns sentences as they come (SSE), the phone starts speaking
  with the first one.
- **Live voice via the agent**: keep Gemini Live for the conversation and hand real work to the
  agent as a tool, as agentring does on calls.
- **Push from the agent**: let the agent speak first (a flight change, a reply that arrived)
  when the glasses are connected.
- **Mock device tests** with the toolkit's simulator, so changes can be checked without glasses.
- Merge upstream Tupo Vision changes regularly.
