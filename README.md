# 简浏览（Android）

**公开发行：v0.5** · **应用版本：2.8.1（versionCode 18）**
简浏览是一款使用 Mozilla GeckoView Stable `157.0.20260924084938` 的 Android 浏览器。发行标签与应用版本号是两套编号；上一公开版为 [v0.4（应用版本 2.6.0）](https://github.com/fghijkln/simple-browser-geckoview/releases/tag/v0.4)。

## 下载

- [arm64-v8a APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-arm64-v8a.apk)
- [x86_64 APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-x86_64.apk)
- [源码、测试与审计 ZIP](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-source.zip) · [SHA-256 清单](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/checksums.txt)
- [构建报告](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-BUILD-REPORT.md) · [应用特权守卫审计](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-PRIVILEGE-GUARD-AUDIT.md) · [网页调试 API 审计](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-WEB-DEBUG-API-AUDIT.md)

APK 按 ABI 分开提供，没有通用 APK；最低 Android API 29、目标 API 35。

## v0.5 的变化

新标签页固定内置七张离线壁纸，并按设备当前时区的本地日历日期**强制每日轮换**。同一天保持同一张图；切换到下一本地日期时前进一张并按七日循环。应用创建或回到前台时立即重算，前台停留期间每 60 秒检查一次。设置页只有说明，没有手动选择、暂停或即时换图入口；无需新增 Android 权限、闹钟或后台服务。

开发者设置还提供两项默认关闭的可选网页调试功能：GeckoView USB/ADB Firefox DevTools 远程调试，以及页内 Console/Errors 面板。远程调试连接方能够检查并操纵页面，只应连接可信设备并在使用后关闭。页内面板需先明确授权、再点击打开；届时会安装随 APK 提供的扩展并请求覆盖所有 HTTP/HTTPS 网站的 host permission。面板只保留固定类别、级别、参数数量及本地顺序号，不读取或显示日志正文、错误正文、堆栈、URL、DOM、表单输入、Cookie 或网络内容；数据只在内存，最多 500 条，清除或关闭即清空。页面可伪造这些有限 metadata，因此记录不是可信审计证据。关闭面板会卸载扩展并重启 Runtime；标签 URL 尽量恢复，但历史和临时页面状态会重置。详见[网页调试说明](source/simple-browser-crawler/docs/WEB-DEBUGGING.md)及[双语发行说明](RELEASE-NOTES-v0.5.md)。

## 保留能力与验证范围

浏览器仍基于 GeckoView，保留受控抓取、搜索目录、网络隐私配置和应用自身特权守卫等原有项目功能；发行没有新增 Android Manifest 权限。详情见[完整 Android 源码与文档](source/simple-browser-crawler/)。

构建和离线回归、APK 签名/ZIP/对齐及静态权限核验不代表真机验证。本版没有连接 Android 设备或模拟器，未声称实测网页 Console 捕获、DevTools 设备连接或设备上的壁纸呈现。APK 使用保留的 Android Debug 签名证书（SHA-256：`e82931a4c130f749fe03fa6f6b530007d03a7b00f003aea47cdc3d0f69e28dfd`），不是应用商店生产签名；覆盖安装需要现有应用使用同一证书。完整边界见[构建报告](BUILD-REPORT.md)和两份审计报告。

---

# Simple Browser (Android)

**Public release: v0.5** · **Application version: 2.8.1 (versionCode 18)**
Simple Browser is an Android browser built on Mozilla GeckoView Stable `157.0.20260924084938`. Release tags and application version numbers are separate; the previous public release was [v0.4 (application version 2.6.0)](https://github.com/fghijkln/simple-browser-geckoview/releases/tag/v0.4).

## Downloads

- [arm64-v8a APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-arm64-v8a.apk)
- [x86_64 APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-x86_64.apk)
- [Source, tests, and audits ZIP](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-source.zip) · [SHA-256 checksums](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/checksums.txt)
- [Build report](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-BUILD-REPORT.md) · [App privilege-guard audit](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-PRIVILEGE-GUARD-AUDIT.md) · [Web-debug API audit](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.5/SimpleBrowser-2.8.1-WEB-DEBUG-API-AUDIT.md)

The APKs are ABI-specific; there is no universal APK. Minimum Android API is 29 and target API is 35.

## What changed in v0.5

The new-tab page uses exactly seven bundled offline wallpapers and **forces one image per device-local calendar date**. The image stays fixed within a date, advances on the next local date, and repeats on a seven-day cycle. The app recalculates at creation and foreground resume, and checks every 60 seconds while foregrounded. Settings provides a non-interactive explanation only—there is no manual selection, pause, or instant-switch entry point. No Android permission, alarm, or background service was added.

Developer settings also offer two optional, default-off debugging features: GeckoView USB/ADB Firefox DevTools remote debugging and an in-app Console/Errors panel. A connected remote-debugging peer can inspect and manipulate pages; use only a trusted device and turn it off afterward. The in-app panel requires explicit consent followed by an Open action; only then does the app install its bundled extension and request host permission for all HTTP/HTTPS sites. It retains only fixed category, level, argument count, and a local ordinal. It does not read or display log/error text, stacks, URLs, DOM, form input, cookies, or network contents. Data stays in memory, capped at 500 entries, and Clear or close removes it. Pages can forge this limited metadata, so it is not trusted audit evidence. Closing uninstalls the extension and restarts the Runtime; tab URLs are restored where possible, but history and transient page state reset. See the [web-debugging guide](source/simple-browser-crawler/docs/WEB-DEBUGGING.en.md) and [bilingual release notes](RELEASE-NOTES-v0.5.md).

## Retained features and verification limits

The browser remains based on GeckoView and retains the project's existing controlled crawler, search catalogue, network-privacy configuration, and app-local privilege guard. This release adds no Android Manifest permissions. See the [Android source and documentation](source/simple-browser-crawler/).

Build/offline-test results, APK signature/ZIP/alignment checks, and static permission review are not real-device testing. No Android device or emulator was connected; real Console capture, DevTools device connectivity, and on-device wallpaper rendering are not claimed as verified. APKs use the retained Android Debug certificate (SHA-256: `e82931a4c130f749fe03fa6f6b530007d03a7b00f003aea47cdc3d0f69e28dfd`), not a production app-store key; an in-place upgrade requires the existing installation to use the same certificate. See the [build report](BUILD-REPORT.md) and audit reports for details.
