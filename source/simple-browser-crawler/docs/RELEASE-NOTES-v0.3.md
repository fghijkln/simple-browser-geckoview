# v0.3 — 简浏览手动权限 / Simple Browser: Manual Permissions

- **应用版本 / Application version:** 2.5.0 (`versionCode` 14)
- **浏览器引擎 / Browser engine:** Mozilla GeckoView Stable 157.0.20260924084938
- **ABI:** `arm64-v8a` and `x86_64` APKs; no universal APK.

## 中文

本版为网站摄像头、麦克风和定位能力声明最小的可选 Android 权限，但不会在启动、浏览或 GeckoView 权限回调中主动申请，也不会弹出 Android 危险权限授权框。需要这些功能时，请先到 Android“设置 > 应用 > 简浏览 > 权限”手动授予；若权限缺失，相关网站请求会被拒绝并给出提示，普通浏览继续。系统授权后，网站仍须通过独立的浏览器站点确认。

Manifest 将摄像头、自动对焦和麦克风硬件特性显式设为非必需，因此无这些硬件的设备仍可安装并使用基础浏览；对应媒体能力在缺少硬件时不可用。

照片、网页上传和扩展 ZIP 选择继续使用 Android 系统文档选择器，只获得用户选定文件的 URI 授权，不读取整个媒体库。剪贴板没有可由用户在“应用权限”中单独授予的常规运行时权限，复制/粘贴由 Android 与 GeckoView 的系统集成及平台前台访问规则约束。

本应用不实现步数/活动识别或心率等身体传感器能力，因此不声明 `ACTIVITY_RECOGNITION`、`BODY_SENSORS` 或 `BODY_SENSORS_BACKGROUND`。普通加速度计/陀螺仪不应被误认为需要上述身体传感器授权；GeckoView 网页运动传感器接口在真实设备上的可用性未验证，本版不作保证。屏幕共享和未列入本版支持清单的 GeckoView Android 权限请求会被拒绝。

可选 DNS-only VPN 仍是独立实验：只有用户主动点击相应设置项时才会触发 Android VPN 系统确认；它不是启动或网页权限回调中的危险权限申请。

本发行还附有详细权限审计（`SimpleBrowser-2.5.0-PERMISSION-AUDIT.md`）、构建报告（`SimpleBrowser-2.5.0-BUILD-REPORT.md`）及 `checksums.txt`。

**验证边界：**本地构建、签名与离线检查状态见随附的构建报告，详细权限边界见权限审计。`adb devices -l` 未发现已连接真机或运行中的模拟器；构建成功也不等于已安装启动、完成 UI/权限弹窗、媒体采集、位置精度、传感器或网络路径的设备验证。APK 使用项目既有 debug signing key，不是商店生产签名。

## English

This release declares only the optional Android permissions needed for website camera, microphone, and location features. The app never actively requests these dangerous permissions during launch, browsing, or GeckoView permission callbacks, and it never opens an Android runtime-permission dialog. To use a feature, manually enable its permission under **Android Settings > Apps > Simple Browser > Permissions**. A website request is denied with an explanation when the corresponding app permission is missing; ordinary browsing remains available. After the OS-level permission is granted, the browser still asks for separate site-level consent.

The manifest marks camera, autofocus, and microphone hardware features as optional, so devices without those peripherals can still install the browser and use basic browsing; the corresponding media feature is unavailable when its hardware is absent.

Photo selection, web uploads, and extension ZIP import continue to use Android's system document picker. Access is limited to the URI selected by the user; the app does not request broad media-library access. Android has no ordinary runtime app permission that users can grant for clipboard access in the app-permissions screen. Copy and paste follow Android/GeckoView system integration and platform foreground-access rules.

The app does not implement step/activity recognition or health/body-sensor features, so it does not declare `ACTIVITY_RECOGNITION`, `BODY_SENSORS`, or `BODY_SENSORS_BACKGROUND`. Ordinary accelerometer and gyroscope use should not be mislabeled as requiring those body-sensor permissions. GeckoView web motion-sensor availability has not been verified on a physical device and is not promised in this release. Screen capture and GeckoView Android permissions outside the supported allowlist are denied.

The optional DNS-only VPN remains a separate experiment. Android's VPN system confirmation is triggered only when the user explicitly selects the VPN setting; it is not an app-startup or webpage permission request.

Additional release assets include the detailed permission audit (`SimpleBrowser-2.5.0-PERMISSION-AUDIT.md`), the build report (`SimpleBrowser-2.5.0-BUILD-REPORT.md`), and `checksums.txt`.

**Verification limits:** The accompanying build report records the local build, signing, and offline checks; the permission audit documents the detailed permission boundaries. `adb devices -l` found no connected phone or running emulator. A successful build does not establish installation, startup, UI, permission-dialog, media-capture, location-accuracy, sensor, or network-path behavior on a device. The APKs use the project's existing debug signing key, not a production app-store key.
