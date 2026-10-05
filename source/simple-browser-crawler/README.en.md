# Simple Browser Android source project (local v0.6 candidate / 2.9.0)

This Android Gradle project is a modified copy based on public v0.5 commit `26a28a72d10a7907e8a995c8f2cf03568765af5d`. **v0.6/2.9.0 is a local candidate only and has not been published remotely; APKs use the Android Debug certificate, not a production app-store key.** Scope, compatibility, remaining technical boundaries, regression/build/Manifest audit, and device-test limits are documented in the repository-root [Chinese report](../../REPORT.md), [Simplified Chinese release notes](../../RELEASE-NOTES-v0.6-zh-CN.md), [English release notes](../../RELEASE-NOTES-v0.6-en.md), and [Manifest audit](../../MANIFEST-AUDIT-v0.6.txt).

## v0.6 candidate changes

A bilingual risk notice appears at startup, lists app-privilege findings, and requires Continue or Exit. Non-fatal guard findings no longer independently block startup; fatal corruption still fails. USB/ADB DevTools and the in-app Console default to available and remain switchable in Settings. Console installation still requests all-sites host permission; top-level page logs/errors/stacks may contain sensitive content. Console row count, per-entry text/argument size, and event rate each offer finite choices or Unlimited; Unlimited remains subject to Android/Java heap and data-type limits.

Web camera, microphone, and location requests can invoke Android runtime permission prompts, followed by the existing per-site decision. Crawling remains explicitly user-started, foreground, and serial, with finite values or `0 = Unlimited` for page count, queue, total duration, per-page/robots bytes, same-origin redirects, request gap, and 429/503 wait. Actual infinite memory is not guaranteed; every 429/503 retry requires confirmation and honors server `Retry-After`. Authentication/CAPTCHA/paywalls are not bypassed, and the crawler does not share browser login state.

No Android Manifest permission was added. Cookie/profile isolation, DNS/ETP/WebRTC defaults, and the user-private storage path were not changed. The notice reminds users of risks; it does not change legal responsibility or authorization status. **No Android device installation/runtime test has been performed.**

## Local build and offline regressions

Requires JDK 21, Gradle Wrapper 9.7.1, Android Gradle Plugin 9.4.0, and Android SDK API 37. The app uses `minSdk 29`, `targetSdk 35` and builds ABI-specific `arm64-v8a` and `x86_64` APKs.

```bash
bash tools/run-crawler-local-mock.sh
bash tools/run-geckoview-local-tests.sh
./gradlew --offline --no-daemon :app:lintRelease :app:assembleRelease
```

Offline tests are not Android-device or real-site tests. Read the repository-root report for the precise meaning of “0 removes an app-configured cap” and the remaining system, memory, and network boundaries.
