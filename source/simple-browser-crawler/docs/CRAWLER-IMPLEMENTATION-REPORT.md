# 受控站点抓取实现与验证

本次基于 `/workspace/simple-browser-fingerprint/` 创建独立副本 `/workspace/simple-browser-crawler/`，实现 **2.4.0 / versionCode 13**。抓取器是菜单中的独立第二信息源，没有并入 502 项搜索引擎目录；原源码目录、原 APK 和已发布内容均未修改。

## 页面范围与硬限制

用户需主动输入一个公开 HTTPS 页面地址。抓取器只访问同一来源站点的 HTTPS 默认端口页面和同源链接；最多发现 12 个链接候选、尝试 4 个页面，整个任务不超过 90 秒，每个页面正文不超过 1 MiB，连接/读取超时各最多 8 秒。请求串行，默认至少间隔 2 秒；站点声明的 `Crawl-delay` 在 30 秒以内会被遵守，若更长则停止而不会缩短。每条请求链最多跟随 3 次同源重定向，逐跳手动核对目标，禁止自动跨站跳转。Android 的 [`HttpURLConnection` API 文档](https://developer.android.com/reference/java/net/HttpURLConnection) 说明自动跳转可以逐连接关闭；源码据此逐跳校验目标。

首次检查同站 `robots.txt`；成功取得的规则（最多 512 KiB）及 404/410 的“未提供”结果只在内存缓存 5 分钟、最多 32 个站点，不持久化页面正文。解析器支持常见 User-Agent 组、`Allow`/`Disallow`、`*` 与 `$` 规则；不处理 sitemap，也不声称覆盖所有百分号编码等解析边缘。任务内对 URL 去重。仅支持静态 HTML、XHTML 与纯文本，抽取标题、最多 240 字符摘要和来源 URL；不运行 JavaScript，不下载页面依赖、图片、PDF 或其他二进制资源，也不自动打开或发布内容。SPA 动态渲染、点击/滚动后加载、登录后页面和付费内容通常不适用。

## 反爬、授权与站点负载

[RFC 9309](https://www.rfc-editor.org/rfc/rfc9309.html) 明确指出 robots 规则**不是访问授权**，并要求成功取得文件时遵守可解析规则；服务端或网络错误导致 robots 不可达时应假定禁止访问。实现对 5xx、403、其他非 404/410 robots 响应采取停止策略；虽 RFC 建议 robots 重定向可跨 Authority 跟随至少 5 跳，本实现为坚持同源范围而拒绝跨站重定向，并在最多 3 跳后停止。404/410 只表示未提供 robots 文件，不构成抓取许可。Google 也说明 robots.txt 主要用于管理抓取流量，不是保护私密页面的访问控制；页面公开可读并不自动授予复制或再发布权，仍须遵守站点使用条件和适用规则。

HTTP 401/403、429、503/其他服务端错误、非支持内容、超限页面和跨站跳转均不会通过代理、IP 轮换、指纹伪装、登录态或验证码绕过来克服。CAPTCHA、登录墙和付费墙由保守的页面文本启发式识别；该检查可能误报或漏报，不能当作可靠识别器。遇到 429 会停止且不自动重试；若响应含 `Retry-After`，界面显示可解析的秒数/日期等待建议。[RFC 6585 §4](https://www.rfc-editor.org/rfc/rfc6585.html) 说明 429 表示限流且可带 Retry-After；[MDN](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Retry-After) 亦说明该字段适用于 429、503 和重定向。本实现对带 Retry-After 的重定向直接停止，不会提前访问目标。

## 网络与浏览器隐私边界

抓取使用独立的 Android Java `HttpURLConnection`，没有从 GeckoView 读取 Cookie/认证资料；源码未配置 `CookieHandler`、`CookieManager`、`Authenticator` 或自定义代理，实际 loopback mock 也验证未发送 Cookie 或 Authorization。它不共享浏览器网页会话，且抓取正文不写入持久缓存。输入与重定向目标会做 HTTPS、默认端口及本机/私有/常见特殊用途地址预检；不过名称预检使用 Android 系统解析，连接阶段由平台网络栈处理，存在 DNS 解析时差/重解析边界。

GeckoView 的 Quad9 `TRR_MODE_ONLY` **不证明** Java HTTP 抓取连接也经由同一 DoH 或可选 DNS-only VPN；因此界面和本报告均不宣称同等 DNS 隐私，也未发送 Quad9 探测请求。没有访问真实反爬检测站或目标站点；所有新增网络边界测试只连本机 loopback mock。

## 验证与交付

完整本地回归通过：502 项搜索目录和既有浏览器/Profile/ETP/DoH/WebRTC 模型测试、DoH loopback TLS mock，以及 crawler mock（robots 规则、同源范围、纯文本保真、无 Cookie/Authorization、页数/字节/时间间隔、短时规则缓存、403/429/503、挑战/访问墙、Retry-After 重定向、跨站重定向和取消）。Release lint 为 **0 errors、2 warnings**；剩余提示是原项目 targetSdk 35 与 Gradle Wrapper 9.7.1 的版本建议，本次未为消除提示擅自升级运行时基线。

两种 APK 均为 `com.cue.simplebrowser` 2.4.0/versionCode 13，分别只含 `arm64-v8a` 或 `x86_64`，V3 APK 签名及 4-byte/16-KB 对齐验证通过。APK 的 DEX/native Chromium/CEF 静态标记扫描无匹配；签名为 Android Debug 证书（与原 2.3.0 APK 相同），**不是应用商店生产签名**。没有可用 Android 设备或模拟器，因此未进行安装、启动、真机网络或 DNS 路径验证。

- [arm64-v8a APK](/workspace/simple-browser-crawler-build/gradle/app/outputs/apk/release/app-arm64-v8a-release.apk) — SHA-256：`99ad4317f4950664c0286d446b6526c30f0a01c047b8f66b322f35fdf00fcc77`
- [x86_64 APK](/workspace/simple-browser-crawler-build/gradle/app/outputs/apk/release/app-x86_64-release.apk) — SHA-256：`2c0b75e136a1b90ea999533e9be91db677fcd0a2626c337f2772989c62792ce6`
- APK 校验：[apk-verification.txt](/workspace/simple-browser-crawler-build/reports/apk-verification.txt)；lint：[lint-results-release.txt](/workspace/simple-browser-crawler-build/gradle/app/reports/lint-results-release.txt)；构建：[gradle-release-build.log](/workspace/simple-browser-crawler-build/reports/gradle-release-build.log)
- 源码与测试包：[simple-browser-crawler-source-and-tests.zip](/workspace/simple-browser-crawler-build/simple-browser-crawler-source-and-tests.zip)；本地回归：[full-source-regression.log](/workspace/simple-browser-crawler-build/reports/full-source-regression.log)
