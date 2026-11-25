#!/usr/bin/env bash
# Example METACLAW_AGENT_CMD for OpenClaw (https://github.com/openclaw/openclaw).
# Asks the agent through the local Gateway with the `openclaw agent` CLI. When the app sent a
# camera frame, the agent is told where the JPEG is so it can open it with its image reader.
#
# Optional environment:
#   OPENCLAW_BIN    path to the openclaw CLI (default: openclaw on PATH)
#   OPENCLAW_AGENT  agent id (default: main)
#   OPENCLAW_MODEL  model override for fast voice turns (default: the agent's own model)
set -euo pipefail

QUESTION="$1"
BIN="${OPENCLAW_BIN:-openclaw}"
AGENT="${OPENCLAW_AGENT:-main}"

PROMPT="[Spoken question from the smart glasses (MetaClaw). Answer in one or two short sentences of plain text, it will be read aloud. No markdown, no lists, no links.]"
if [[ -n "${METACLAW_LANGUAGE:-}" ]]; then
  PROMPT+=$'\n'"Answer in the language of the question (device language: ${METACLAW_LANGUAGE})."
fi
if [[ -n "${METACLAW_LOCATION:-}" ]]; then
  IFS=, read -r LAT LON ACC AGE <<<"$METACLAW_LOCATION"
  PROMPT+=$'\n'"The user's phone is at latitude ${LAT}, longitude ${LON} (accuracy ${ACC} m, fix ${AGE} s old). Use it for questions about where they are or what is nearby; do not mention the coordinates."
fi
if [[ -n "${METACLAW_IMAGE:-}" ]]; then
  PROMPT+=$'\n'"The glasses camera took this picture of what the user is looking at: ${METACLAW_IMAGE} . Open it with your image reader before answering."
fi
PROMPT+=$'\n\n'"Question: ${QUESTION}"

ARGS=(agent --agent "$AGENT" --session-id "metaclaw-${METACLAW_CONVERSATION_ID:-default}" --thinking off --message "$PROMPT")
if [[ -n "${OPENCLAW_MODEL:-}" ]]; then ARGS+=(--model "$OPENCLAW_MODEL"); fi

exec "$BIN" "${ARGS[@]}"
