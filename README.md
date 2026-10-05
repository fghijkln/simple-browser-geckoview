# 简浏览 / Simple Browser (Android)

**对外发布版本：v0.3** · **应用内部版本：2.5.0（versionCode 14）**
**Public release: v0.3** · **Application version: 2.5.0 (versionCode 14)**

简浏览是一款简体中文 Android 浏览器，使用 Mozilla GeckoView Stable `157.0.20260924084938`。本次检查的 APK 中未发现 Chromium 或 CEF；`libxul.so`、`libmozglue.so` 等是 GeckoView 的 Mozilla 引擎组件。上一公开版本为 [v0.2（应用版本 2.4.0 / versionCode 13）](https://github.com/fghijkln/simple-browser-geckoview/releases/tag/v0.2)，其标签与发行资产保留不变。

Simple Browser is a Simplified Chinese Android browser built on Mozilla GeckoView Stable `157.0.20260924084938`. The inspected APKs contain no Chromium or CEF; `libxul.so` and `libmozglue.so` are Mozilla GeckoView engine components. The previous public release was [v0.2 (application version 2.4.0 / versionCode 13)](https://github.com/fghijkln/simple-browser-geckoview/releases/tag/v0.2); its tag and release assets remain unchanged.

## 下载 / Downloads

- [arm64-v8a APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.3/SimpleBrowser-2.5.0-arm64-v8a.apk)
- [x86_64 APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.3/SimpleBrowser-2.5.0-x86_64.apk)
- [源码与测试 ZIP](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.3/SimpleBrowser-2.5.0-source-and-tests.zip) · 仓库源码：[source/simple-browser-crawler/](source/simple-browser-crawler/)

These are ABI-specific APKs; there is no universal APK. Minimum Android API is 29 and target API is 35. SHA-256 values for the v0.3 release assets are listed in [`checksums.txt`](checksums.txt).

## 功能与技术

- 原生 Android 浏览器界面，支持标签、新标签页、前进/后退、刷新、主页、历史记录、书签和 HTTPS 下载。
- 地址栏可输入网址或搜索词；内置 **502 项搜索目录**、21 个常用搜索引擎图标，以及自定义 HTTPS 搜索模板。v0.2 保留原目录，不把抓取器合并进搜索引擎列表。
- 菜单中另有独立的**受控网页抓取第二信息源**：从用户主动输入的公开 HTTPS 页面及同源链接抽取静态文本；上限为每项任务 4 页、90 秒、每页 1 MiB，串行请求至少间隔 2 秒。抓取器不是通用搜索引擎，也不承诺绕过反爬或访问限制；细节见 [`source/simple-browser-crawler/docs/CRAWLER-IMPLEMENTATION-REPORT.md`](source/simple-browser-crawler/docs/CRAWLER-IMPLEMENTATION-REPORT.md)。
- 新标签页提供 14 张离线壁纸；可选择本机照片并设置每日轮换。电脑版请求模式只更改 GeckoView User-Agent，不模拟桌面视口。
- 本地环境管理器可创建、切换、改名、查看配置摘要和删除环境；切换会关闭当前标签。历史、书签和请求模式按环境保存。扩展 ZIP 仅供导入、查看声明和移除；Firefox 扩展运行时未实现，扩展代码不会执行。
- 隐私设置采用 GeckoView 原生 Strict Tracking Protection、HTTPS-only，以及 Quad9 TRR-only：`https://dns.quad9.net/dns-query`。可选 DNS-only VPN 原型需要用户主动触发 Android 系统授权；它不是通用流量 VPN。
- 摄像头、麦克风与定位为可选权限：应用只在 Manifest 声明，由用户去 Android 系统设置手动授予；应用不主动弹运行时授权框。摄像头、自动对焦和麦克风硬件特性显式设为非必需，因此无这些硬件仍可安装并进行基础浏览；缺少授权或硬件时只拒绝对应媒体请求，普通浏览继续；授予后仍须逐站确认。照片/文件选择使用系统文档选择器，不读取整个媒体库。详情见[`双语发行说明`](https://github.com/fghijkln/simple-browser-geckoview/releases/tag/v0.3)与随包源码权限文档。

## 受控抓取范围与限制

抓取器只接受用户主动提交的公开 HTTPS 页面，只访问同源 HTTPS 默认端口页面；每条请求链最多跟随 3 次同源重定向，并逐跳检查目标。每项任务最多尝试 4 页、发现 12 个链接候选、总计 90 秒，每页正文最多 1 MiB；请求串行，默认至少间隔 2 秒，连接和读取超时各最多 8 秒。站点声明的 `Crawl-delay` 不超过 30 秒时会遵守；更长时停止。

首次请求同站 `robots.txt`。成功取得的规则以及 404/410 的“未提供”结果仅在内存中缓存 5 分钟、最多 32 个站点；规则文件上限 512 KiB。抓取器支持常见 User-Agent 组、`Allow`、`Disallow`、`*` 和 `$`，不处理 sitemap，也不覆盖所有编码边缘。robots 规则不是访问授权；robots 不可达时停止，404/410 也不表示获得抓取许可。公开可读页面不自动授予复制或再发布权，仍须遵守站点条款及适用规则。

仅支持静态 HTML、XHTML 和纯文本，提取标题、最多 240 字符摘要与来源 URL；不运行 JavaScript，不抓取图片、PDF、页面依赖或其他二进制内容，也不自动打开或发布结果。SPA 动态渲染、点击/滚动后加载、登录后页面和付费内容通常不适用。401/403、429、503 或其他服务端错误、跨站跳转、超限或不支持的内容都会停止；429 不自动重试。挑战页、CAPTCHA、登录墙和付费墙通过保守文本启发式检查，可能误报或漏报。

抓取使用独立的 Android Java `HttpURLConnection` 和系统名称解析，不读取 GeckoView Cookie 或认证资料；抓取正文不写入持久缓存。不会使用代理、IP 轮换、浏览器登录态、指纹伪装或验证码绕过。输入及重定向目标会预检 HTTPS、默认端口和可识别的本机/私有/特殊用途地址，但 DNS 预检与实际连接之间仍有解析时差/重解析边界。GeckoView 的 Quad9 `TRR_MODE_ONLY` **不证明** Java HTTP 抓取也经由 Quad9 或 DNS-only VPN；本项目不宣称抓取器具有同等 DNS 隐私。

## 环境隔离与隐私边界

每个环境的数据目录位于 app-private 的 `filesDir/fingerprint-profiles/<UUID>/`。GeckoView 157 没有公开的 Java 多 profile API；本项目通过公开的通用 `arguments(...)` 接口传入 Gecko `-profile` 参数。这个实现仍属实验性：该参数在目标 Android 设备上的行为，以及 Cookie、缓存和站点存储是否真正写入各自目录，均未在设备上验证。因此不能把这些环境描述为完整或强隔离，也不建议用它们隔离高风险账号。

v0.1 旧版默认浏览器数据没有迁移界面，也不会自动迁移或拆分到新环境。

Locale 与时区是创建环境时读取的 Android 系统快照，不是独立设定；设备硬件、系统构建、GeckoView 引擎、图形、屏幕和字体等特征不会被伪装成不同设备。本应用不是反指纹设备，也不提供反自动化或反欺诈绕过能力。

应用配置 GeckoView Strict tracking protection、HTTPS-only 和 Quad9 TRR-only，但配置本身不证明实际设备流量路径。DoH 查询会交给 Quad9 解析，也不等同匿名化。WebRTC 关闭使用 Mozilla 标为 **Experimental** 的 Gecko preference，未经设备上的 ICE/IP 泄漏测试。GeckoView DNS 是否经过 VPN 隧道同样未经设备验证；VPN 需要用户在 Android 系统界面授权。

旧扩展不执行；跟踪防护由 GeckoView 原生功能承担，逐站例外选项有限或不可用。严格跟踪防护可能影响登录、嵌入内容或结账页面。抓取器不共享浏览器 Cookie，但这不代表连接的 DNS 路径已隔离或匿名化。

## 验证与已知限制

本次为 **build-only + offline tests**：完整本地回归通过，包含搜索目录、浏览器/Profile/ETP/DoH/WebRTC 模型和 crawler loopback mock 测试；DoH 测试使用本地 mock。Release lint 为 **0 errors、2 warnings**，提示是既有 targetSdk 35 与 Gradle Wrapper 9.7.1 的版本建议。未使用 Android 真机或模拟器，APK 未安装或启动；没有验证真实目标站点、真实反爬检测或设备 DNS 路径。

两个 APK 使用 Android **debug key** 签名，不是商店生产签名；核验结果为 `v3=true`、`v2=false`，并通过 4-byte 与 16-KB 对齐检查。由不同密钥签名的既有安装可能无法覆盖升级。静态扫描未发现 Chromium/CEF 标记。构建或离线测试结果不代表已验证真机网络行为。

GeckoView 官方许可为 MPL-2.0，构建依赖还包括 Apache-2.0 组件；对应第三方许可文本与通知随 APK 提供。项目根目录没有自定义的顶层 `LICENSE` 文件；这些第三方许可信息不构成对整个应用代码的统一许可声明。Mozilla 与 GeckoView 均不对本项目品牌作背书。

---

# Simple Browser (Android)

**Public release: v0.3** · **Application version: 2.5.0 (versionCode 14)**

## Downloads

- [arm64-v8a APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.3/SimpleBrowser-2.5.0-arm64-v8a.apk)
- [x86_64 APK](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.3/SimpleBrowser-2.5.0-x86_64.apk)
- [Source and tests ZIP](https://github.com/fghijkln/simple-browser-geckoview/releases/download/v0.3/SimpleBrowser-2.5.0-source-and-tests.zip) · proposed source tree: [`source/simple-browser-crawler/`](source/simple-browser-crawler/)

These are ABI-specific APKs; there is no universal APK. Minimum Android API is 29 and target API is 35. SHA-256 values for the v0.3 assets are listed in [`checksums.txt`](checksums.txt). The previous public release was [v0.2 (application version 2.4.0 / versionCode 13)](https://github.com/fghijkln/simple-browser-geckoview/releases/tag/v0.2); its tag and release assets remain unchanged.

## Features and stack

- Native Android browser UI with tabs, new tabs, back/forward, reload, home, history, bookmarks, and HTTPS downloads.
- The address bar accepts URLs or search terms. The app retains its **502-entry search catalogue**, 21 bundled search-engine icons, and custom HTTPS search templates; the crawler is not merged into that catalogue.
- A separate **controlled page-crawling second source** is available from the menu. It extracts static text from a user-entered public HTTPS page and same-origin links, with hard limits of 4 pages, 90 seconds per task, 1 MiB per page, and serial requests at least 2 seconds apart. It is not a general-purpose search engine and does not promise to bypass anti-crawling or access restrictions. See [`source/simple-browser-crawler/docs/CRAWLER-IMPLEMENTATION-REPORT.md`](source/simple-browser-crawler/docs/CRAWLER-IMPLEMENTATION-REPORT.md).
- The new-tab page has 14 offline wallpapers, with local photo selection and daily rotation. Desktop-request mode changes only the GeckoView User-Agent; it does not emulate a desktop viewport.
- A local profile manager can create, switch, rename, inspect a configuration summary for, and remove environments; switching closes the current tabs. History, bookmarks, and request mode are profile-specific. Extension ZIPs can be imported for inspection and removed, but the Firefox extension runtime is not implemented and extension code is not executed.
- Privacy settings use GeckoView's native Strict Tracking Protection, HTTPS-only mode, and Quad9 TRR-only at `https://dns.quad9.net/dns-query`. An optional DNS-only VPN prototype requires user-triggered Android system authorization; it is not a general-purpose traffic VPN.
- Camera, microphone, and location permissions are optional and manually granted in Android Settings. The app never opens a dangerous runtime-permission request; without a grant, only the relevant sensitive website request is denied and normal browsing remains available. Camera, autofocus, and microphone hardware features are explicitly optional, so devices without those peripherals remain installable for basic browsing. A separate site-consent prompt appears after an OS grant. Photo/file selection uses Android's system document picker, not broad media-library access. See the [bilingual v0.3 release notes](https://github.com/fghijkln/simple-browser-geckoview/releases/tag/v0.3) and the source permission audit.

## Manual app permissions and device capabilities

To use website camera, microphone, or geolocation features, manually enable the corresponding app permission under **Android Settings > Apps > Simple Browser > Permissions**. Missing permissions are checked and denied without an OS runtime prompt. Camera, autofocus, and microphone hardware features are explicitly optional, so the app remains installable on devices without those peripherals. Website consent remains a separate GeckoView decision. Android's document picker grants access only to the user-selected file URI, so no broad media permission is needed. Android has no ordinary app-permissions toggle for clipboard access. This app does not implement step/activity recognition or health/body sensors; it does not declare `ACTIVITY_RECOGNITION` or `BODY_SENSORS`. Ordinary accelerometer and gyroscope use should not be mislabeled as requiring those permissions. GeckoView web motion-sensor behavior has not been device-tested and is not promised. The optional DNS-only VPN is a separate, user-triggered system authorization flow.

## Crawler scope and limitations

The crawler accepts only a user-submitted public HTTPS page and follows same-origin HTTPS pages on the default port. It manually checks each redirect and follows at most 3 same-origin redirects per request chain. Each task attempts at most 4 pages, discovers at most 12 link candidates, runs for at most 90 seconds, and reads no more than 1 MiB of page body per page. Requests are sequential, normally at least 2 seconds apart, with connect and read timeouts of up to 8 seconds each. A declared `Crawl-delay` up to 30 seconds is honored; a longer delay stops the crawl.

The crawler first requests the site's `robots.txt`. Successfully retrieved rules and a 404/410 “not provided” result are cached in memory only for 5 minutes, with at most 32 sites; the file limit is 512 KiB. Common User-Agent groups, `Allow`, `Disallow`, `*`, and `$` are supported; sitemaps and every percent-encoding edge case are not. Robots rules are not authorization. The crawler stops if robots is unavailable; a 404/410 does not grant permission. Public readability does not automatically grant rights to copy or republish content.

Only static HTML, XHTML, and plain text are supported. The crawler extracts a title, a summary of up to 240 characters, and the source URL; it does not run JavaScript, fetch images, PDFs, page dependencies, or other binary resources, or automatically open or publish results. SPAs, content loaded after clicks or scrolling, authenticated pages, and paywalled content are generally unsuitable. 401/403, 429, 503 or other server errors, cross-origin redirects, oversized pages, and unsupported content stop the crawl; 429 is not retried automatically. Challenge pages, CAPTCHAs, login walls, and paywalls are detected by conservative text heuristics that can produce false positives or negatives.

The crawler uses a separate Android Java `HttpURLConnection` path and system name resolution; it does not read GeckoView cookies or authentication data, and page bodies are not written to persistent cache. It does not use proxies, IP rotation, browser login state, fingerprint spoofing, or CAPTCHA bypass. Input and redirect targets are prechecked for HTTPS, the default port, and recognizable local/private/special-use addresses, but DNS preflight and connection resolution have timing/re-resolution boundaries. GeckoView's Quad9 `TRR_MODE_ONLY` does **not prove** that Java HTTP crawling uses the same Quad9 or DNS-only VPN path; equivalent DNS privacy is not claimed.

## Profile isolation and privacy boundaries

Each environment has a directory under the app-private `filesDir/fingerprint-profiles/<UUID>/`. GeckoView 157 exposes no public Java multi-profile API; this project passes Gecko's `-profile` argument through the public generic `arguments(...)` interface. This remains experimental: the argument's behavior on target Android devices and whether cookies, cache, and site storage actually land in separate directories have not been verified on a device. These environments should not be described as complete or strong isolation, and they are not recommended for separating high-risk accounts.

There is no migration UI for the v0.1 default-browser data; it is not automatically migrated or split into the new environments.

Locale and time zone are snapshots of Android system settings when an environment is created, not independent settings. Device hardware, OS build, GeckoView engine, graphics, screen, fonts, and other characteristics are not made to look like different devices. This is not an anti-fingerprinting device and does not provide anti-automation or anti-fraud bypass features.

GeckoView is configured for Strict Tracking Protection, HTTPS-only, and Quad9 TRR-only, but configuration alone does not verify actual device traffic. DoH sends queries to Quad9 and is not anonymization. WebRTC is disabled using a Gecko preference Mozilla labels **Experimental**; no on-device ICE/IP leak test was performed. Whether GeckoView DNS traverses the VPN tunnel is also unverified. Android requires the user's system-level VPN authorization.

Legacy extensions do not run; tracking protection uses GeckoView's native features, and per-site exceptions are limited or unavailable. Strict tracking protection may affect sign-ins, embedded content, or checkout pages. The crawler does not share browser cookies, but this does not mean its DNS path is isolated or anonymized.

## Verification and known limitations

This was **build-only + offline-test** validation: the full local regression suite passed, covering the search catalogue, browser/profile/ETP/DoH/WebRTC models, and crawler loopback mocks; DoH tests used local mocks. Release lint reported **0 errors and 2 warnings**, which are recommendations concerning the existing targetSdk 35 and Gradle Wrapper 9.7.1. No Android device or emulator was used; the APKs were not installed or launched. Real target sites, real anti-crawler detection, and on-device DNS paths were not tested.

Both APKs use an Android **debug key**, not a production store key. Verification reported `v3=true` and `v2=false`, and passed 4-byte and 16-KB alignment checks. An in-place upgrade may fail if an existing build was signed with a different key. Static scans found no Chromium/CEF markers. Build and offline-test results do not verify on-device network behavior.

GeckoView is licensed under MPL-2.0, and build dependencies include Apache-2.0 components; their third-party license texts and notices are included with the APKs. The project has no custom top-level `LICENSE` file; these third-party notices are not a blanket license statement for the entire application code. Mozilla and GeckoView do not represent or endorse this project.
