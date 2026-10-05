# Simple Browser Android source project (v0.5 / 2.8.1)

This directory contains the complete Android Gradle project for public release **v0.5** (application version **2.8.1 / versionCode 18**). The repository's [bilingual overview](../../README.md) has downloads, feature summaries, and verification limits; [bilingual release notes](../../RELEASE-NOTES-v0.5.md) describe the wallpaper and web-debugging changes.

The new-tab page contains seven bundled offline wallpapers and forces one image per device-local calendar date; there is no manual switch. USB/ADB Firefox DevTools and the in-app Console/Errors panel in Developer settings are both off by default. The Console extension is installed only after the user explicitly opens the panel, which requires accepting host permission for every HTTP/HTTPS site. The panel keeps restricted metadata in memory only, never log text or page URLs. Closing clears memory, uninstalls the extension, and restarts the Runtime. See the [English web-debugging guide](docs/WEB-DEBUGGING.en.md), [中文说明](docs/WEB-DEBUGGING.md), and [web-debug API audit](evidence/GECKOVIEW-WEB-DEBUG-API-AUDIT.md) for details and limitations.

The Android Manifest and app-local privilege guard retain their reviewed v0.4 implementation; daily rotation and web debugging add no Android permissions. Release APKs use the project's retained Android Debug signing certificate, not a production app-store key. Offline builds and static checks are not device tests; real debugging connectivity and wallpaper rendering were not device-verified. See the repository-root [build report](../../BUILD-REPORT.md) for the full test record.

## Build locally

Requires JDK 21, Gradle Wrapper 9.7.1, Android Gradle Plugin 9.4.0, and Android SDK API 37 (minor 2). The app uses `minSdk 29` and `targetSdk 35` and builds only ABI-specific `arm64-v8a` and `x86_64` APKs.

```bash
bash tools/run-geckoview-local-tests.sh
./gradlew --no-daemon :app:lintRelease :app:assembleRelease
```

Offline regression coverage includes the search catalogue, controlled crawler, profiles, wallpaper rotation, permission policy, and web debugging. See the [privilege-guard audit](docs/PRIVILEGE-GUARD-AUDIT.md) for the app-local checks' scope.
