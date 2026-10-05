#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes"
javac -encoding UTF-8 -d "$TMP/classes" \
  "$ROOT/app/src/main/java/com/cue/simplebrowser/WebRtcProtectionPolicy.java" \
  "$ROOT/tools/WebRtcProtectionSmokeTest.java"
java -cp "$TMP/classes" com.cue.simplebrowser.WebRtcProtectionSmokeTest "$ROOT"
