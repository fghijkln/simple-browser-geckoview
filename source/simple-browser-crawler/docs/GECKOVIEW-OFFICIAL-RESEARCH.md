# GeckoView 官方资料核查

资料核查日期：2026-10-04。项目锁定 GeckoView Stable `157.0.20260924084938`；Mozilla 当时的[官方 Maven 元数据](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml)将该版本列为 latest/release。Stable AAR 从 Mozilla 官方仓库 `https://maven.mozilla.org/maven2/` 获取，坐标为 `org.mozilla.geckoview:geckoview:157.0.20260924084938`，不是 Maven Central 坐标。消费者配置见 [GeckoView Quick Start](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/geckoview-quick-start.html)。

该 AAR 声明最低 API 26；项目仍设为 minSdk 29。AAR 元数据要求 compile SDK API 37、minor 1；本项目使用 Android API 37 minor 2，已由 Android Gradle Plugin 9.4.0 的 `compileSdkMinor` 属性通过元数据检查。Mozilla Quick Start 建议 Java source/target compatibility 17。AAR 包含 `libxul.so`、`libmozglue.so` 等 Gecko native 库，并提供 arm64-v8a、x86_64 和 armeabi-v7a；本项目只输出 arm64-v8a 与 x86_64。它们是 Mozilla Gecko 引擎库，不是 Chromium/CEF 的 `libcef.so`。

## GeckoView 中的跟踪与权限设置

GeckoView 会话级 `setUseTrackingProtection(true)` 只开启会话保护，不能独自选择严格级别。严格级别由 `ContentBlocking.Settings` 提供：`EtpLevel.STRICT`、`AntiTracking.STRICT`、严格社交跟踪保护与查询参数剥离；项目把这些配置传给 `GeckoRuntimeSettings.Builder.contentBlocking(...)`。Mozilla 的 [EtpLevel 文档](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/ContentBlocking.EtpLevel.html)说明严格模式使用默认保护列表并可能破坏网站；[AntiTracking 文档](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/ContentBlocking.AntiTracking.html)描述其单独的跟踪器拦截选项。应用仍未实现逐站例外、Cookie 分区或防指纹承诺。

`GeckoSession.PermissionDelegate.onMediaPermissionRequest` 提供 `callback.reject()` 路径。应用对 Android 权限、站点权限和网页媒体请求均无条件拒绝，不申请摄像头或麦克风权限，也没有网页权限授予界面。该安全边界不取决于 WebRTC 开关。

## WebRTC

GeckoView 没有稳定的专用 `disableWebRTC` 或 `setWebRTCEnabled` runtime API。Mozilla 的 [`RTCPeerConnection.webidl`](https://searchfox.org/firefox-main/source/dom/webidl/RTCPeerConnection.webidl)以 `media.peerconnection.enabled` 偏好控制 PeerConnection Web API。应用使用 [`GeckoPreferenceController`](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoPreferenceController.html) 请求关闭该偏好，同时保留页面 JavaScript；该通用偏好接口标为 **Experimental**，不是稳定的 WebRTC API。

如果设置请求返回失败，应用会关闭网页 JavaScript作为 fail-closed 回退。请求成功仅代表偏好设置 API 回报成功，不证明已有或并发 ICE 会话已终止，也不证明设备的 ICE/IP 流量未泄漏。Mozilla 的 [`all.js`](https://searchfox.org/firefox-main/source/modules/libpref/init/all.js)还定义了若干 ICE 地址偏好，但它们不是经过项目验证的 GeckoView 网络隔离控制；此应用不把它们宣称为零泄漏保证。

## HTTPS-only 与 DNS-over-HTTPS

[GeckoRuntimeSettings](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.html)提供 `allowInsecureConnections(HTTPS_ONLY)`、`trustedRecursiveResolverMode(TRR_MODE_ONLY)` 和 `trustedRecursiveResolverUri(...)`。DoH API 与 HTTPS-only API 是不同设置；本项目分别设置 HTTPS-only 和 Quad9 URI `https://dns.quad9.net/dns-query`。Mozilla 的 [DNS-over-HTTPS/TRR 文档](https://firefox-source-docs.mozilla.org/networking/dns/dns-over-https-trr.html)区分 TRR-only 与可回退模式，并列出 excluded domains、网络 DNS suffix、captive-portal/IPv6 能力探测、`/etc/hosts` 和 bootstrap 等例外。因此源码配置不能证明所有设备请求都通过 DoH，也不能推出零泄漏。DoH 不解析 IP 字面地址、不会隐藏最终网站连接目标，也不控制网站自带的 DoH/DoT。

项目未访问公共 DNS/WebRTC 泄漏检测站，也未向 Quad9 发送测试查询；RFC 8484 测试仅连接本机 loopback HTTPS mock。真实设备上的 Gecko DNS 路径和 WebRTC ICE 行为尚未验证。

## 许可与再分发

该坐标的 [Mozilla 官方 Maven POM](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/157.0.20260924084938/geckoview-157.0.20260924084938.pom)声明 Mozilla Public License 2.0。APK 随包提供完整 MPL-2.0 文本和第三方通知。MPL 第 3 节规定了分发 Covered Software 二进制时提供对应 Source Code Form 的义务；本项目记录的上游 `mozilla-release` 修订为 `8eb25af4acf031ab1e06abf1a912275083c820ed`，可在 [Mozilla 官方修订页](https://hg.mozilla.org/releases/mozilla-release/rev/8eb25af4acf031ab1e06abf1a912275083c820ed)获取源码。

APK 的完整运行依赖树包含 73 个唯一坐标；逐项解析版本和各 Maven POM 中的许可字段见 [`DEPENDENCY-LICENSE-AUDIT.md`](DEPENDENCY-LICENSE-AUDIT.md)。该审计把没有许可字段的 POM 标成“未声明”，不把它误判为无许可。AndroidX 及其他构件的许可证不可统称为 MPL-2.0；APK 另提供 Apache-2.0 全文和第三方通知。