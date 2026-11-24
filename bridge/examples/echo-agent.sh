#!/usr/bin/env bash
# Test agent: repeats the question, says whether a picture came with it. Use it to check the
# app and the bridge before wiring a real agent.
set -euo pipefail
if [[ -n "${METACLAW_IMAGE:-}" ]]; then
  echo "You asked: $1. I got a picture of $(stat -c %s "$METACLAW_IMAGE") bytes."
else
  echo "You asked: $1"
fi
