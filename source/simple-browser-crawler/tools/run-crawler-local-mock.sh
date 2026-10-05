#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RESULTS="$ROOT/test-results"
BUILD_ROOT="${SIMPLE_BROWSER_BUILD_ROOT:-$ROOT/../simple-browser-crawler-build}"
mkdir -p "$RESULTS" "$BUILD_ROOT/tmp-crawler-test-classes"
TMP="$BUILD_ROOT/tmp-crawler-test-classes"
find "$TMP" -type f -delete
javac --add-modules jdk.httpserver -encoding UTF-8 -d "$TMP" \
  "$ROOT/app/src/main/java/com/cue/simplebrowser/ControlledCrawler.java" \
  "$ROOT/app/src/main/java/com/cue/simplebrowser/CrawlerHtmlParser.java" \
  "$ROOT/tools/ControlledCrawlerSmokeTest.java"
java --add-modules jdk.httpserver -cp "$TMP" \
  com.cue.simplebrowser.ControlledCrawlerSmokeTest \
  2>&1 | tee "$RESULTS/crawler-local-mock-test.log"
