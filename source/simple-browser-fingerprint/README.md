# 简浏览（Android）2.3.0

本项目是一个简体中文 Android 浏览器，内核为 Mozilla GeckoView Stable `157.0.20260924084938`，不使用系统 WebView，也不包含 Cefrium、CEF、Chromium runtime 或 `libcef.so`。APK 中出现 Mozilla GeckoView 自有 native 库（例如 `libxul.so`、`libmozglue.so`）属于预期：它们是 Gecko 引擎，不是 Chromium。版本、Maven 坐标、Android 要求、安全 API 与许可证来源见 [`docs/GECKOVIEW-OFFICIAL-RESEARCH.md`](docs/GECKOVIEW-OFFICIAL-RESEARCH.md)。

## 浏览器功能

- 原生新标签页、标签切换、后退/前进、停止/刷新、主页，以及带用户手势的 HTTP(S) 弹窗在新标签页打开。标签在当前所选环境中共用一个 GeckoRuntime；环境管理器通过关闭会话并重启 app process 切换 profile。当前没有跨设备同步。项目没有独立验证 GeckoView 内部遥测/远程配置网络行为，不据此断言引擎没有后台连接。
- 地址栏接受网址或搜索词。搜索目录包含 **502 项**，支持多级分类、功能筛选及访问方式筛选；21 个常用引擎图标随 APK 本地提供。自定义搜索引擎只保存 HTTPS URL 模板，不下载或执行代码。
- 提供 profile 级本地浏览历史、书签与网站手机/电脑版请求模式，以及搜索模板管理、设置和开源许可证查看。电脑版模式只更换 GeckoView User-Agent，不模拟桌面视口。环境管理支持新建、切换、改名、查看配置摘要和双重确认删除；切换会关闭当前标签。
- 新标签页包含 14 张离线壁纸，支持本机照片选择和每日轮换；不从网络下载壁纸。
- HTTPS 下载保存到设备下载目录，并拒绝非 HTTPS、超过 256 MiB 或无法完整写入的文件。
- MV3 扩展 ZIP 只可导入、查看声明并移除；不会安装、授予其权限或执行其中代码。Firefox 扩展运行时并未实现，GeckoView 扩展 Web API 也关闭。

## 环境隔离的含义与边界

每个环境有独立随机 UUID，Gecko 启动参数 `-profile` 指向 `filesDir/fingerprint-profiles/<UUID>/`；可编辑名称不参与文件路径。历史/书签/UA 请求模式按 profile 保存。切换在新进程中重建 GeckoRuntime；旧版默认 Gecko profile 和旧共享历史/书签不自动迁移或删除。Android backup 与 device-transfer 数据规则排除 app-private 文件、数据库和偏好。

GeckoView 157 没有公开的 dedicated Java multi-profile API；实现依赖官方通用 Runtime `arguments(...)` 向 Gecko 主进程传递 `-profile`。官方 API 依据与限制见 [`docs/GECKOVIEW-PROFILE-API-AUDIT.md`](docs/GECKOVIEW-PROFILE-API-AUDIT.md)。环境是本地资料分区，不是不同 Android 设备：OS/build、Gecko 引擎、硬件/图形、屏幕和字体等可见特征可能共享。locale/timezone 是每个环境创建时的系统快照，默认 UA 为 GeckoView 移动模式；模板是可查证的来源信息，不通过硬件伪造声称构造不同指纹，也不针对 anti-fraud/anti-bot 绕过。

删除使用 durable tombstone，先清除环境自己的偏好元数据，再递归移除 UUID Gecko 目录；异常时启动后重试。行为说明与未验证范围见 [`docs/FINGERPRINT-PROFILES.md`](docs/FINGERPRINT-PROFILES.md)。**本轮没有 Android 设备或 AVD，尚未实测 GeckoView 是否将 Cookie、缓存和站点存储实际写入各自的 `-profile` 目录，不能把离线目录测试等同真机隔离验证。**

## 隐私与安全设置

应用创建 GeckoRuntime 时启用 GeckoView **ETP Strict、AntiTracking Strict、严格社交跟踪保护和查询参数剥离**，并打开 Global Privacy Control。它不再打包旧的六域名本地过滤表或 Public Suffix List，也没有经过验证的逐站例外界面；原生 ETP 的具体规则来自 GeckoView，并可能影响登录、嵌入内容或结账页面。严格跟踪保护不是 Cookie/存储隔离、防指纹或全面的匿名化保证。

GeckoView 配置为 HTTPS-only，并设置 `TRR_MODE_ONLY` 与 Quad9 DoH 地址 `https://dns.quad9.net/dns-query`。这让 Gecko 的常规名称解析优先只尝试该 DoH resolver，而不使用普通 native DNS 回退；Mozilla 同时列出 excluded domains、网络 DNS suffix、captive-portal/IPv6 能力探测、`/etc/hosts` 和 bootstrap 等例外。源代码配置不等于真机流量验证，应用状态也明确显示 **“GeckoView DNS 路径未验证”**。DoH 不隐藏访问网站的 IP 连接、不会解析 IP 字面地址，也不控制网站自有 DoH/DoT 或应用外流量。

Quad9 的[数据与隐私政策](https://quad9.net/privacy/policy/)（版本 1.1，2026-06-24）称，服务必须在内存中短暂处理回复地址后即删除，不记录用户 IP；该政策也允许保留长期聚合计数，其中可能含查询标签及首次/最近时间，并说明它不会把这些数据关联到单个用户。查询域名仍会交给 Quad9 解析；DoH 是传输加密，不是匿名化。政策范围和元数据边界见 [`docs/NETWORK-PRIVACY-AUDIT.md`](docs/NETWORK-PRIVACY-AUDIT.md) 与 [`docs/QUAD9-DATA-BOUNDARY.md`](docs/QUAD9-DATA-BOUNDARY.md)。

WebRTC PeerConnection 防护默认开启：应用通过 Mozilla 标记为 **Experimental** 的 `GeckoPreferenceController` 请求关闭 `media.peerconnection.enabled`。若偏好设置 API 返回失败，应用会关闭网页 JavaScript 作为 fail-closed 回退；成功返回也不等于真机 ICE 流量已验证。网页媒体、Android 权限请求始终拒绝，且应用不申请摄像头/麦克风权限。该设置不是网络层 UDP 隔离，不承诺零 WebRTC/IP 泄漏。

可选 DNS-only VPN 是独立实验原型，需 Android 显示系统授权；它只尝试处理发往指定 DNS resolver 地址的端口 53 流量，不接管通用流量。VPN 图标或 DoH 请求成功都不能证明 GeckoView 的 DNS 已进入该隧道。没有访问公网 DNS/WebRTC 泄漏检测站，也没有向 Quad9 发送测试查询；DoH 测试使用 loopback HTTPS mock。

GeckoView 官方许可为 MPL-2.0，构建依赖还包括 Apache-2.0 组件。许可证文本与第三方通知随 APK 提供。GeckoView 对应源码获取链接与再分发说明见上述官方资料记录及 APK 内 `THIRD-PARTY-NOTICES.txt`；Mozilla 与 GeckoView 均不对本项目品牌作背书。

## 构建与本地测试

需要 JDK 21、Gradle Wrapper 9.7.1、Android Gradle Plugin 9.4.0 以及 Android SDK API 37（本项目使用 minor SDK 2）。最低 Android 版本为 API 29，目标 API 35；仅生成 `arm64-v8a` 与 `x86_64` 两个 APK，不生成通用包。默认构建输出到仓库同级的 `simple-browser-fingerprint-build/gradle/`；可设置 `SIMPLE_BROWSER_BUILD_ROOT` 更改该目录。

```bash
bash tools/run-geckoview-local-tests.sh
bash tools/run-dns-doh-local-test.sh
./gradlew --no-daemon :app:lintRelease :app:assembleRelease
```

Release 任务使用当前项目的 Android debug signing key，**不是应用商店生产签名**。因应用最低版本为 API 29，本次 `apksigner` 报告的 Signature Scheme v3 为真、v2 为假；不要把它描述为同时具有 v2 与 v3 签名。APK 核验、哈希、lint/build 日志和运行限制随本次构建报告交付。当前环境没有可用 Android 设备/模拟器，所以构建与离线测试不代表已安装、启动或真机网络行为通过。
