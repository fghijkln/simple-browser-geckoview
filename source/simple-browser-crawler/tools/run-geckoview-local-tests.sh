#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
RESULTS="$ROOT/test-results"
mkdir -p "$RESULTS"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes"

javac -encoding UTF-8 -d "$TMP/classes" \
  app/src/main/java/com/cue/simplebrowser/MiniJson.java \
  app/src/main/java/com/cue/simplebrowser/SearchEngine.java \
  app/src/main/java/com/cue/simplebrowser/SearchEngineCatalog.java \
  app/src/main/java/com/cue/simplebrowser/CustomEngineStore.java \
  app/src/main/java/com/cue/simplebrowser/UrlMatchPattern.java \
  app/src/main/java/com/cue/simplebrowser/Mv3ExtensionManifest.java \
  app/src/main/java/com/cue/simplebrowser/LocalExtensionArchive.java \
  app/src/main/java/com/cue/simplebrowser/SiteMode.java \
  app/src/main/java/com/cue/simplebrowser/BrowserTabRegistry.java \
  app/src/main/java/com/cue/simplebrowser/BrowserUiModel.java \
  app/src/main/java/com/cue/simplebrowser/BrowserDataStore.java \
  app/src/main/java/com/cue/simplebrowser/BrowserProfileStore.java \
  app/src/main/java/com/cue/simplebrowser/WallpaperRotation.java \
  app/src/main/java/com/cue/simplebrowser/OverflowActionDispatcher.java \
  app/src/main/java/com/cue/simplebrowser/WebRtcProtectionPolicy.java \
  app/src/main/java/com/cue/simplebrowser/WebDebugPolicy.java \
  app/src/main/java/com/cue/simplebrowser/WebConsoleEntry.java \
  app/src/main/java/com/cue/simplebrowser/WebConsoleBuffer.java \
  tools/SearchEngineSmokeTest.java \
  tools/Mv3ExtensionSmokeTest.java \
  tools/StoreAndSiteModeSmokeTest.java \
  tools/BrowserTabRegistrySmokeTest.java \
  tools/BrowserUiModelSmokeTest.java \
  tools/BrowserDataStoreSmokeTest.java \
  tools/BrowserProfileStoreSmokeTest.java \
  tools/BrowserWindowLayoutSmokeTest.java \
  tools/WallpaperRotationSmokeTest.java \
  tools/OverflowActionDispatcherSmokeTest.java \
  tools/WebDebugPolicySmokeTest.java \
  tools/WebConsoleBufferSmokeTest.java \
  tools/WebRtcProtectionSmokeTest.java

{
  java -cp "$TMP/classes" com.cue.simplebrowser.SearchEngineSmokeTest \
    "$ROOT/app/src/main/assets" "$ROOT/app/src/main/assets/engine-icons/PROVENANCE.tsv"
  java -cp "$TMP/classes" com.cue.simplebrowser.Mv3ExtensionSmokeTest
  java -cp "$TMP/classes" com.cue.simplebrowser.StoreAndSiteModeSmokeTest
  java -cp "$TMP/classes" com.cue.simplebrowser.BrowserTabRegistrySmokeTest
  java -cp "$TMP/classes" com.cue.simplebrowser.BrowserUiModelSmokeTest
  java -cp "$TMP/classes" com.cue.simplebrowser.BrowserDataStoreSmokeTest
  java -cp "$TMP/classes" com.cue.simplebrowser.BrowserProfileStoreSmokeTest
  java -cp "$TMP/classes" com.cue.simplebrowser.WebRtcProtectionSmokeTest "$ROOT"
  java -cp "$TMP/classes" com.cue.simplebrowser.BrowserWindowLayoutSmokeTest \
    "$ROOT/app/src/main/java/com/cue/simplebrowser/MainActivity.java" \
    "$ROOT/app/src/main/res/drawable-nodpi/new_tab_wallpaper.png" \
    "$ROOT/app/src/main/res/values/strings.xml"
  java -cp "$TMP/classes" com.cue.simplebrowser.WallpaperRotationSmokeTest \
    "$ROOT/app/src/main/java/com/cue/simplebrowser/MainActivity.java" \
    "$ROOT/app/src/main/res/values/strings.xml" "$ROOT/app/src/main/assets" \
    "$ROOT/app/src/main/res/drawable-nodpi/new_tab_wallpaper.png"
  java -cp "$TMP/classes" com.cue.simplebrowser.OverflowActionDispatcherSmokeTest
  java -cp "$TMP/classes" com.cue.simplebrowser.WebDebugPolicySmokeTest \
    "$ROOT/app/src/main/java/com/cue/simplebrowser/GeckoViewBrowserAdapter.java" \
    "$ROOT/app/src/main/AndroidManifest.xml" \
    "$ROOT/app/src/main/java/com/cue/simplebrowser/BrowserPrivilegeGuard.java" \
    "$ROOT/app/src/main/assets/web_console/manifest.json" \
    "$ROOT/app/src/main/assets/web_console/main-world.js" \
    "$ROOT/app/src/main/assets/web_console/relay.js" \
    "$ROOT/app/src/main/java/com/cue/simplebrowser/MainActivity.java"
  java -cp "$TMP/classes" com.cue.simplebrowser.WebConsoleBufferSmokeTest \
    "$ROOT/app/src/main/java/com/cue/simplebrowser/MainActivity.java" \
    "$ROOT/app/src/main/java/com/cue/simplebrowser/GeckoViewBrowserAdapter.java"
  node "$ROOT/tools/test-web-console.js"
  python3 "$ROOT/tools/privilege_guard_policy_smoke.py"
  python3 "$ROOT/tools/manual_permission_policy_smoke.py"
} 2>&1 | tee "$RESULTS/geckoview-local-tests.log"
