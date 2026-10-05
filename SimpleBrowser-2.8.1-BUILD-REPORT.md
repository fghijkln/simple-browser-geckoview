# Simple Browser v0.5 / 2.8.1 Build and Verification Report

**Application:** `com.cue.simplebrowser` · **Version:** 2.8.1 (`versionCode 18`) · **Proposed public tag:** `v0.5` (not created)
**Release baseline:** remote `main` / `v0.4`, commit `5738a77a3e4b9b22b6c4437f266a5fb053d2b8fa`.
**Status:** local release-candidate only; no remote branch, tag, Release, or asset upload was created.

## Final integrated build

The build was run from the release-draft clone's `source/simple-browser-crawler/` project, not from the earlier standalone candidate folder. Offline regression `bash tools/run-geckoview-local-tests.sh` passed. It covers the fixed seven-wallpaper local-date cycle and no-picker behavior; web-debug default-off and explicit panel-open gating; message/UI field allowlists, ring-buffer and rate limits, clearing, and sensitive-string exclusion; retained permission policy, browser/profile, search, and related UI models. Supplementary `bash tools/run-dns-doh-local-test.sh`, `bash tools/run-crawler-local-mock.sh`, and `bash tools/run-webrtc-protection-smoke.sh` also passed. DNS tests used loopback-only mocks (0 cleartext fallbacks and 0 external/Quad9 queries); crawler tests used loopback only. Logs are included in the evidence bundle.

The Release build used JDK 21 and Android SDK build tools 37.0.0 with the offline command:

```bash
./gradlew --offline --no-daemon :app:lintRelease :app:assembleRelease --console plain
```

Gradle completed successfully (`BUILD SUCCESSFUL`; 49 actionable tasks). `lintRelease` reported **0 errors and 2 warnings**: `OldTargetApi` for the intentionally configured `targetSdk 35`, and `ObsoleteSdkInt` for an existing SDK guard under `minSdk 29`. No warning was suppressed as part of this release preparation.

## APK verification

Both APKs passed the project's final verification script: package/version/ABI checks; v3 signature verification; ZIP integrity; four-byte and 16 KB native-library alignment; ABI-exclusive native payload checks; and static scans for Chromium/CEF package, DEX, and native-library markers. The APKs contain Mozilla GeckoView libraries, including `libxul.so` and `libmozglue.so`, as expected.

| Release asset | Size (bytes) | SHA-256 |
|---|---:|---|
| `SimpleBrowser-2.8.1-arm64-v8a.apk` | 218730367 | `6d3eed9b83f43a5ef7498063c82fbcabfef919e9be745dbe15d4f20a8cd616b5` |
| `SimpleBrowser-2.8.1-x86_64.apk` | 238093720 | `05e56d772796cb2fd8569ab2087a81f0d7d6995f36250b936f12839e08d0f6ba` |

Each APK is `com.cue.simplebrowser`, version 2.8.1 / code 18, and contains only its named ABI. Both verify with APK Signature Scheme v3. Their signing certificate SHA-256 is `e82931a4c130f749fe03fa6f6b530007d03a7b00f003aea47cdc3d0f69e28dfd`, exactly matching the available `v0.4` arm64-v8a and x86_64 reference APKs. This is the retained Android Debug certificate, **not** a production app-store signing key.

## Manifest and app privilege review

For each ABI, `aapt dump permissions` shows the same 11 requested permissions as the v0.4 APK. The full merged Manifest tree also matches the v0.4 reference after normalizing only the expected `versionName` and `versionCode` fields; all remaining attributes, components, and declarations compare exactly. The candidate source `AndroidManifest.xml` and `BrowserPrivilegeGuard.java` are byte-identical to the remote v0.4 source. No Android permission, app component, Wi-Fi/LAN listener, persistent service, or release `android:debuggable` flag was added. See `source/simple-browser-crawler/evidence/merged-manifest-v0.5-final-audit.txt` and the separate privilege-guard audit.

## Wallpaper, web debugging, and limits

The integrated 2.8.1 source and APK include the seven-image, forced local-calendar-date wallpaper cycle; settings exposes no manual wallpaper switch. Both web-debug modes remain opt-in and default-off. Installing the in-app Console extension requires accepting all-site HTTP(S) host permission, which is broader than actual capture. Console data is restricted to allowlisted metadata and stays in memory; a page can forge that metadata. Remote DevTools peers can inspect and manipulate pages. Closing the Console clears data, uninstalls the extension, and restarts the Gecko Runtime, with possible loss of back/forward history and transient page state.

`adb devices -l` reported no connected Android devices. Therefore this build does **not** claim device verification of wallpaper rendering, real GeckoView Console injection/capture, or USB/ADB DevTools discovery/connectivity. Static analysis and offline tests cannot establish those runtime behaviors.

Final offline test output, lint/build log, APK verification, manifest comparison, and device-list result are included under `source/simple-browser-crawler/evidence/` and `test-results/`. The release asset `checksums.txt` will contain exact hashes for both APKs, the source ZIP, this report, and the two audit reports.
