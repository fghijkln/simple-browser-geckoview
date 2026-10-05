# 简浏览 / Simple Browser (Android)

**外部发布版本：v0.1** · **应用内部版本：2.3.0（versionCode 12）**

简浏览是一款简体中文 Android 浏览器，使用 Mozilla GeckoView `157.0.20260924084938`。本次检查的 APK 中未发现 Chromium 或 CEF；APK 内的 `libxul.so`、`libmozglue.so` 等是 GeckoView 的 Mozilla 引擎组件。

## 下载

- [arm64-v8a APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.1/app-arm64-v8a-release.apk)
- [x86_64 APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.1/app-x86_64-release.apk)

这是两个按 ABI 分开的 APK，不提供 universal APK。最低 Android 版本为 API 29；目标 API 为 35。SHA-256 校验值见仓库中的 [`checksums.txt`](checksums.txt)。

## 功能与技术

- 原生 Android 浏览器界面，支持标签、新标签页、前进/后退、刷新、主页、历史记录、书签和 HTTPS 下载。
- 地址栏可输入网址或搜索词；内置 502 项搜索目录、21 个常用搜索引擎图标，以及自定义 HTTPS 搜索模板。
- 新标签页提供 14 张离线壁纸；可选择本机照片并设置每日轮换。电脑版请求模式只更改 GeckoView User-Agent，不模拟桌面视口。
- 环境管理器可创建、切换、改名和删除本地环境。扩展 ZIP 仅供导入、查看声明和移除；Firefox 扩展运行时未实现，扩展代码不会执行。
- 隐私设置采用 GeckoView 原生 Strict Tracking Protection、HTTPS-only，以及 Quad9 TRR-only：`https://dns.quad9.net/dns-query`。
- 可选 DNS-only VPN 原型需要 Android 系统授权；它不是通用流量 VPN。

应用为 Android 原生工程，`applicationId` 为 `com.cue.simplebrowser`，最低 API 29、目标 API 35，依赖 GeckoView 157。构建使用 Gradle Wrapper 9.7.1 和 Android Gradle Plugin 9.4.0；源码兼容级别为 Java 17。

## 环境隔离与隐私边界

每个环境的数据目录位于 app-private 的 `filesDir/fingerprint-profiles/<UUID>/`。GeckoView 157 没有公开的 Java 多 profile API；本项目通过公开的通用 `arguments(...)` 接口传入 Gecko `-profile` 参数。这个实现仍属实验性：该参数在目标 Android 设备上的行为，以及 Cookie、缓存和站点存储是否真正写入各自目录，均未在设备上验证。因此不能把这些环境描述为完整或强隔离，也不建议用它们隔离高风险账号。

Locale 与时区是创建环境时读取的 Android 系统快照，不是独立设定；设备硬件、系统构建、GeckoView 引擎、图形、屏幕和字体等特征不会被伪装成不同设备。本应用不是反指纹设备，也不提供反自动化或反欺诈绕过能力。

应用配置 GeckoView Strict tracking protection、HTTPS-only 和 Quad9 TRR-only，但配置本身不证明实际设备流量路径。DoH 查询会交给 Quad9 解析，也不等同匿名化。WebRTC 关闭使用 Mozilla 标为 **Experimental** 的 Gecko preference，未经设备上的 ICE/IP 泄漏测试。GeckoView DNS 是否经过 VPN 隧道同样未经设备验证；VPN 需要用户在 Android 系统界面授权。

旧扩展不执行；跟踪防护由 GeckoView 原生功能承担，逐站例外选项有限或不可用。严格跟踪防护可能影响登录、嵌入内容或结账页面。

## 验证与已知限制

本次验证为 **build-only + offline tests**，没有可用 Android 设备或模拟器，APK 未安装或启动。离线 GeckoView regression 测试为 **29 项通过**；本地 DoH mock 测试为 **37 项通过**。Release 构建成功，lint 为 **0 errors、1 warning**。本地 DoH mock 结果不能代替真实网络、Quad9 或 VPN 路径验证。

两个 APK 使用 Android **debug key** 签名，不是商店生产签名；签名方案为 `v3=true`、`v2=false`。如果设备上已有由不同密钥签名的构建，可能无法直接覆盖升级安装。构建报告中的 lint warning 是 `targetSdk 35` 低于当前 lint 数据库建议的最新 target。

Release 标签 `v0.1` 是对外发布版本号；APK 内部仍报告 `2.3.0` / `versionCode 12`。旧版默认浏览器数据没有迁移 UI，也不会自动拆分到新环境。

GeckoView 使用 MPL-2.0，其他依赖可能采用各自许可证；APK 内含第三方通知。Mozilla 与 GeckoView 不代表或背书本项目。

---

# Simple Browser (Android)

**Public release: v0.1** · **Application version: 2.3.0 (versionCode 12)**

Simple Browser is a Simplified Chinese Android browser built on Mozilla GeckoView `157.0.20260924084938`. The APK inspection found no Chromium or CEF; files such as `libxul.so` and `libmozglue.so` are Mozilla GeckoView engine components.

## Downloads

- [arm64-v8a APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.1/app-arm64-v8a-release.apk)
- [x86_64 APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.1/app-x86_64-release.apk)

These are ABI-specific APKs; there is no universal APK. Minimum Android API is 29 and target API is 35. SHA-256 values are listed in the repository's [`checksums.txt`](checksums.txt).

## Features and stack

- Native Android browser UI with tabs, new tabs, back/forward, reload, home, history, bookmarks, and HTTPS downloads.
- The address bar accepts URLs or search terms. The app includes a 502-entry search catalogue, 21 bundled search-engine icons, and custom HTTPS search templates.
- The new-tab page has 14 offline wallpapers, with local photo selection and daily rotation. Desktop-request mode changes only the GeckoView User-Agent; it does not emulate a desktop viewport.
- A profile manager creates, switches, renames, and removes local environments. Extension ZIPs can be imported for inspection and removed, but the Firefox extension runtime is not implemented and extension code is not executed.
- Privacy settings use GeckoView's native Strict Tracking Protection, HTTPS-only mode, and Quad9 TRR-only at `https://dns.quad9.net/dns-query`.
- An optional DNS-only VPN prototype requires Android system authorization; it is not a general-purpose traffic VPN.

This is a native Android app (`applicationId`: `com.cue.simplebrowser`), with minimum API 29 and target API 35, using GeckoView 157. The build uses Gradle Wrapper 9.7.1 and Android Gradle Plugin 9.4.0; source compatibility is Java 17.

## Profile isolation and privacy boundaries

Each environment has a directory under the app-private `filesDir/fingerprint-profiles/<UUID>/`. GeckoView 157 exposes no public Java multi-profile API; this project passes Gecko's `-profile` argument through the public generic `arguments(...)` interface. This remains experimental: the argument's behavior on target Android devices and whether cookies, cache, and site storage actually land in separate directories have not been verified on a device. These environments should not be described as complete or strong isolation, and they are not recommended for separating high-risk accounts.

Locale and time zone are snapshots of Android system settings when an environment is created, not independent settings. Device hardware, OS build, GeckoView engine, graphics, screen, fonts, and other characteristics are not made to look like different devices. This is not an anti-fingerprinting device and does not provide anti-automation or anti-fraud bypass features.

GeckoView is configured for Strict Tracking Protection, HTTPS-only, and Quad9 TRR-only, but configuration alone does not verify actual device traffic. DoH sends queries to Quad9 and is not anonymization. WebRTC is disabled using a Gecko preference Mozilla labels **Experimental**; no on-device ICE/IP leak test was performed. Whether GeckoView DNS traverses the VPN tunnel is also unverified. Android requires the user's system-level VPN authorization.

Legacy extensions do not run; tracking protection uses GeckoView's native features, and per-site exceptions are limited or unavailable. Strict tracking protection may affect sign-ins, embedded content, or checkout pages.

## Verification and known limitations

This was a **build-only + offline-test** validation: no Android device or emulator was available, and the APKs were not installed or launched. The offline GeckoView regression suite passed **29 tests**; the local DoH mock suite passed **37 tests**. The release build succeeded and lint reported **0 errors and 1 warning**. Local DoH mocks do not replace real-network, Quad9, or VPN-path verification.

Both APKs are signed with an Android **debug key**, not a production store key. The reported signing schemes are `v3=true` and `v2=false`. An in-place upgrade may fail if a device has a build signed with a different key. The lint warning is that `targetSdk 35` is below the latest target recommended by the current lint database.

The public release tag `v0.1` is distinct from the APK's internal version, which remains `2.3.0` / `versionCode 12`. There is no migration UI for old default-browser data, and old data is not automatically split into new environments.

GeckoView is licensed under MPL-2.0; other dependencies may have their own licenses. Third-party notices are included in the APK. Mozilla and GeckoView do not represent or endorse this project.
