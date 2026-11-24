# Plan

## v0.1 (this version)

- [x] Fork Tupo Vision, own application id, builds locally.
- [x] Agent mode: question → bridge → agent → spoken answer.
- [x] Wake phrases "hey Claw" / "oye Claw", with Spanish sound-alikes.
- [x] Camera frame only for visual questions (or always in photo mode).
- [x] Spoken filler after 4 s without an answer.
- [x] Bridge: token auth, one run per conversation, no content in logs, temp frames deleted.
- [x] Offline wake phrase with Vosk (small Spanish model), beep, then the question. Unit tests and
  a synthetic-speech test (`tools/wake_test.py`, 124/129, no false wakes).
- [x] Bridge running as a service on the agent's machine, tested end to end with OpenClaw (text and
  camera frame).
- [ ] Wake phrase with real voices through the glasses microphone; tune thresholds.
- [ ] First run on real glasses (Ray-Ban Meta, Pixel).
- [ ] Measure latency end to end and tune the filler delay.

## Next

- **One-breath questions with the offline wake phrase**: today you wait for the beep. Keep the
  audio after the phrase and hand it to the recogniser, or recognise the whole question offline.
- **Streaming answers**: bridge returns sentences as they come (SSE), the phone starts speaking
  with the first one.
- **Live voice via the agent**: keep Gemini Live for the conversation and hand real work to the
  agent as a tool, as agentring does on calls.
- **Push from the agent**: let the agent speak first (a flight change, a reply that arrived)
  when the glasses are connected.
- **Mock device tests** with the toolkit's simulator, so changes can be checked without glasses.
- Merge upstream Tupo Vision changes regularly.
