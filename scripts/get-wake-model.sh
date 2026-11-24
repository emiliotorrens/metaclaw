#!/usr/bin/env bash
# Downloads the offline wake-phrase model (Vosk small Spanish 0.42, ~40 MB, Apache 2.0, by Alpha Cephei)
# into the app's assets. Run once before building; the CI build runs it too. Checks the SHA-256.
# Without it the app still builds and falls back to the phone's speech recognition for the wake phrase.
# Idea from CJ's scripts/get-wake-model.sh (MIT, Ghanshyam Paunikar).
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=vosk-model-small-es-0.42
SHA256=09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f
DEST=app/src/main/assets/model-es
if [ -f "$DEST/uuid" ] && [ "$(cat "$DEST/uuid")" = "$NAME" ]; then echo "Wake-phrase model already in $DEST"; exit 0; fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fSL --retry 3 -o "$TMP/model.zip" "https://alphacephei.com/vosk/models/$NAME.zip"
echo "$SHA256  $TMP/model.zip" | sha256sum -c -
unzip -q "$TMP/model.zip" -d "$TMP"
mkdir -p "$(dirname "$DEST")"
rm -rf "$DEST"
mv "$TMP/$NAME" "$DEST"
# Vosk's StorageService compares this file to decide whether to unpack the model again.
echo "$NAME" > "$DEST/uuid"
echo "Wake-phrase model ready in $DEST"
