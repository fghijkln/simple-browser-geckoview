# Simple Browser for Android 2.6.0

Simple Browser is a Simplified-Chinese Android browser built on Mozilla GeckoView Stable `157.0.20260924084938`. It does not use Android System WebView and does not include a Chromium/CEF runtime. Mozilla Gecko libraries such as `libxul.so` and `libmozglue.so` are expected parts of GeckoView. See the project documentation for dependency, Android API, security, and licensing details.

## App-local privilege guard

This version performs fail-closed checks on **this app and its current process** during application startup, before an Activity resumes, and when the optional DNS-only VPN service starts. The merged requested-permission set must match the reviewed allowlist. The guard checks the app's dangerous-permission grants, selected special App Ops, and app-specific device-administrator, accessibility-service, and notification-listener state. It rejects a process whose UID is root or shell and requires the effective Linux capability fields `CapEff`, `CapPrm`, `CapInh`, and `CapAmb` to be readable and zero. If a required system value cannot be read or parsed, startup is denied. See [`docs/PRIVILEGE-GUARD-AUDIT.md`](docs/PRIVILEGE-GUARD-AUDIT.md).

The guard only checks this app; **it does not detect whether the whole device is rooted**. It cannot defend against a kernel or operating system already controlled by an attacker, modified or deceptive system APIs, or runtime code injection. It is not a continuous background monitor: a change made while the app remains open is ordinarily detected at the next startup, Activity-resume, or VPN-service check.

GeckoView 157's isolated content process and all-site Fission are configured as defense-in-depth. These are configuration-level claims only; this candidate was not run on a physical device or emulator. No connected Android device or emulator was available for this build.

## Permissions and VPN behavior retained

The optional camera, microphone, and coarse/fine location permissions remain available for users to grant manually in Android settings; the app does not request them through a runtime permission dialog. Website requests still follow the browser's separate site-level decision. The optional DNS-only VPN is retained and starts only after an explicit user action and Android's VPN consent flow. It does not tunnel general traffic.

The app does not request broad media or storage access; user-selected files use Android's system document picker. Unsupported GeckoView permission requests are denied. Some checks concern special system access rather than ordinary runtime permissions; the app does not add or request device-owner, accessibility, or notification-listener services as part of this release.

## Build and verification

The project uses Gradle Wrapper 9.7.1, Android Gradle Plugin 9.4.0, Android SDK API 37 (minor 2), `minSdk 29`, and `targetSdk 35`. It produces ABI-specific `arm64-v8a` and `x86_64` APKs, not a universal APK.

```bash
bash tools/run-geckoview-local-tests.sh
bash tools/run-dns-doh-local-test.sh
bash tools/run-crawler-local-mock.sh
./gradlew --no-daemon :app:lintRelease :app:assembleRelease
```

The existing release signing configuration uses the project's Android debug key, not a production app-store signing key. Candidate APKs must match the v0.3 certificate identity. Build, lint, offline tests, ZIP/signature/alignment checks, and SHA-256 results are recorded in the accompanying build report. Static verification and successful compilation do not establish installation, startup, UI, permission-dialog, Gecko process-isolation, Fission, or real-device network behavior.
