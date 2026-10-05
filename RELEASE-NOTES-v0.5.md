## 简体中文

**简浏览 v0.5** · 应用版本 **2.8.1（versionCode 18）** · Android 29+

### 新增与变化

- **新标签页强制每日壁纸轮换：**七张离线内置图片按设备当前时区的本地日历日期固定映射；同一天保持不变，跨到下一本地日期前进一张，七日循环。应用创建或回到前台时重算，前台期间每 60 秒检查一次。没有手动选择或即时切换入口；未新增 Android 权限、精确闹钟或后台服务。
- **可选网页调试：**USB/ADB Firefox DevTools 远程调试与页内 Console/Errors 面板均默认关闭。远程调试需明确启用；已连接的可信对端可以检查并操纵页面，使用完毕请关闭。
- **页内 Console 的权限和隐私：**设置开关仅记录授权；只有明确点击“打开当前标签 Console 面板”才会安装随 APK 提供的扩展。安装需要接受 `http://*/*` 与 `https://*/*` 全站 host permission，授权范围宽于实际采集范围。面板只对当前选中会话的顶层 HTTP(S) 页面工作；仅显示固定类别、级别、参数数量和应用本地顺序号，不读取/转发日志正文、错误正文、堆栈、路径、URL、DOM、输入、Cookie 或网络内容。数据仅在内存，最多 500 条，每秒最多接收 60 条；Clear 与关闭会清空。网页可伪造这类有限 metadata，因此它不是可信审计日志。关闭会停止采集、清空记录、卸载扩展并重启 Runtime；标签 URL 尽量恢复，但前进/后退历史与临时页面状态会重置。

### 下载与验证说明

- [arm64-v8a APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-arm64-v8a.apk)
- [x86_64 APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-x86_64.apk)
- [源码、回归测试与审计 ZIP](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-source.zip)
- [SHA-256 校验清单](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/checksums.txt) · [构建报告](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-BUILD-REPORT.md) · [特权守卫审计](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-PRIVILEGE-GUARD-AUDIT.md) · [网页调试 API 审计](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-WEB-DEBUG-API-AUDIT.md)

APK 仅提供 `arm64-v8a` 与 `x86_64` 两个 ABI，没有通用 APK；目标 SDK 为 35。离线回归、lint、构建、签名/Manifest/ZIP/对齐检查不是设备测试：本版未在 Android 真机或模拟器上安装，未验证实际 Console 注入/采集、DevTools 发现与连接或壁纸呈现。APK 使用与既有 v0.4 相同的 Android Debug 证书，**不是应用商店生产签名**；覆盖安装需要现有安装使用同一证书。

---

## English

**Simple Browser v0.5** · Application version **2.8.1 (versionCode 18)** · Android 29+

### Changes

- **Forced daily new-tab wallpaper rotation:** seven bundled offline images are mapped to the device's current local calendar date. The image stays fixed within a date, advances on the next local date, and repeats in a seven-day cycle. The app recalculates on creation and foreground resume, and checks every 60 seconds while foregrounded. There is no manual selection or instant-switch entry point; no Android permission, exact alarm, or background service was added.
- **Optional web debugging:** USB/ADB Firefox DevTools remote debugging and the in-app Console/Errors panel are both off by default. Remote debugging requires explicit enablement; a connected trusted peer can inspect and manipulate pages, so disable it when finished.
- **Console permission and privacy:** the settings switch records consent only. The APK-bundled extension is installed only after the user explicitly selects **Open current tab Console panel**. Installation requires accepting all-site host permission for `http://*/*` and `https://*/*`, broader than the actual capture scope. The panel works only for the selected session's top-level HTTP(S) page. It displays fixed category, level, argument count, and an app-local ordinal only; it does not read or forward log/error text, stacks, paths, URLs, DOM, input, cookies, or network contents. Data stays in memory, capped at 500 entries and 60 accepted events per second; Clear and close erase it. A page can forge this limited metadata, so it is not a trusted audit log. Closing stops capture, clears entries, uninstalls the extension, and restarts the Runtime; tab URLs are restored where possible, but back/forward history and transient page state reset.

### Downloads and verification notes

- [arm64-v8a APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-arm64-v8a.apk)
- [x86_64 APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-x86_64.apk)
- [Source, regression tests, and audits ZIP](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-source.zip)
- [SHA-256 checksums](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/checksums.txt) · [Build report](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-BUILD-REPORT.md) · [Privilege-guard audit](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-PRIVILEGE-GUARD-AUDIT.md) · [Web-debug API audit](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-WEB-DEBUG-API-AUDIT.md)

Only ABI-specific `arm64-v8a` and `x86_64` APKs are provided; there is no universal APK. The target SDK is 35. Offline tests, lint, build, signature/Manifest/ZIP/alignment checks are not device testing: this release was not installed on a physical Android device or emulator, and real Console injection/capture, DevTools discovery/connectivity, and wallpaper rendering are not claimed as device-verified. APKs use the same Android Debug certificate as the existing v0.4 release, **not a production app-store signing key**; an in-place upgrade requires the installed app to use the same certificate.
