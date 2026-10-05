# GeckoView 网络隐私与安全核查（2.2.0）

2.2.0 使用 GeckoView `157.0.20260924084938`。本版本把严格内容防护、HTTPS-only 和 DoH 配置放在 GeckoRuntime 设置中；旧 Cefrium/CEF/Chromium runtime 与本地六域名跟踪过滤实现已移除。官方 API、稳定版坐标、Android 要求和许可证依据见 [`GECKOVIEW-OFFICIAL-RESEARCH.md`](GECKOVIEW-OFFICIAL-RESEARCH.md)。

## 已配置的浏览器保护

运行时启用 `ContentBlocking.EtpLevel.STRICT`、`AntiTracking.STRICT`、严格社交跟踪保护和 URL 查询参数剥离。GeckoView 自带这些跟踪列表；应用没有可验证的逐站例外 setter，也没有打包旧版本地规则或 Public Suffix List。严格模式可能破坏登录、嵌入内容和结账页面，不能解读为 Cookie 分区、跨标签隔离、防指纹或完整匿名化。

GeckoRuntime 同时打开 Global Privacy Control、HTTPS-only 与远程调试关闭，并配置 Quad9 DoH URI `https://dns.quad9.net/dns-query` 及 `TRR_MODE_ONLY`。Mozilla 对该模式的定义是不使用 native resolver 回退，但 DNS 行为仍有 excluded domains、网络 DNS suffix、captive-portal/IPv6 能力探测、`/etc/hosts` 和 bootstrap 等例外。因此，源码设置说明的是预期策略，不证明设备上的每一次名称解析都走 DoH。应用对应状态始终显示“GeckoView DNS 路径未验证”。

WebRTC 保护通过 Mozilla 的通用 `GeckoPreferenceController` 请求将 `media.peerconnection.enabled` 设为 `false`，以期在保留网页 JavaScript 时禁用 PeerConnection。此 API 标注为 **Experimental**，没有稳定的专用 WebRTC 开关。若设置 API 报错或回调失败，应用关闭网页 JavaScript作为 fail-closed 回退；网页媒体、站点权限和 Android 权限请求另行无条件拒绝。偏好 API 成功回调并不等于 ICE/IP 路径经过设备验证，也不能替代网络层 UDP 规则或保证零泄漏。

## Quad9：解析数据与元数据

DoH 会把查询内容交给所选递归解析服务商处理。HTTPS 保护客户端与 resolver 之间传输中的查询，不会让 resolver 看不到域名，也不会隐藏客户端连接到 Quad9 的目的 IP、时间和流量大小；DoH 本身不提供匿名网络路由。

Quad9 的[数据与隐私政策](https://quad9.net/privacy/policy/)现行版本 1.1 于 2026-06-24 发布。按该政策，回复地址（resolver 收到的源 IP）会在服务单次请求期间留在易失内存中，完成响应后即删除；Quad9 表示不把用户 IP 或替代性个人标识符写入持久记录。政策同时描述了可能长期保存的聚合数据，例如按地点、网络前缀、协议、QTYPE、响应码和过滤状态统计的计数；还可能保存每个查询标签的计数及首次/最近时间。Quad9 将这些数据定义为不包含可识别个人的信息，并称不会与单一用户关联。这是服务商对其处理方式的公开承诺，不是本应用独立审计所得的结论。

同一政策允许将有限统计数据反馈给曾提供特定恶意域名情报的合作方，也允许发布去标识化聚合趋势；其承诺不分享个人可识别信息，并说明不会分享 NXDOMAIN 的主机名级查询标签。Quad9 FAQ 亦称其不记录客户端 IP，但政策另指出异常攻击等情形可能适用独立规则。因此应准确表述为“Quad9 政策声称不持久记录用户 IP，但保留聚合 DNS 统计”，而不是笼统地称其“不记录任何数据”或“完全无日志”。更多定义见 [Quad9 FAQ](https://quad9.net/support/faq/) 和[完整政策正文](https://quad9.net/privacy/policy/)。

## DNS-only VPN 实验原型

应用另保留一个可选的 DNS-only VPN 服务。启用时 Android 会单独显示系统 VPN 授权界面；该服务不添加默认路由，只添加指向特定 resolver 地址的 DNS 路由，并通过 RFC 8484 HTTPS POST 转发捕获的 DNS 查询。若服务启动失败，不回退到明文 DNS；服务状态用“实验”标记，并明确显示 GeckoView DNS 路径未验证。

这不是完整流量 VPN，也不是 GeckoView DNS 已进入 TUN 的证明。VPN 图标只证明系统建立了这条选择性隧道；GeckoView 可能采用其 runtime DoH、bootstrap 或其他系统路径。IP 字面网址不产生 DNS 查询；网页或其他应用可自带解析器。DoH 也不会隐藏最终网页连接的 IP 地址。无需从“DNS-only VPN 已连接”推导全局防泄漏结论。

## 移除的旧实现与保留的功能

此版本不再执行旧本地广告/跟踪域名过滤规则；旧规则与 PSL 文件不在 APK 中。GeckoView 原生 ETP 取代该过滤器。外部 MV3 ZIP 仍可导入、解析权限声明、供用户查看或删除，但绝不会安装、授予权限或运行内容。GeckoView 扩展 Web API 处于关闭状态。

保留的浏览功能包括多标签、历史、书签、包含 502 项的搜索目录和本机自定义搜索模板、HTTPS 下载、壁纸及网站显示模式。应用不设置 JavaScript 原生桥接；网页媒体权限恒拒绝。站点、系统、DNS 服务商和目的站点仍可能看到其协议所必需的信息，应用也不声称跨标签 Cookie 隔离。

## 测试结果与未完成验证

纯本地 GeckoView 回归脚本的 **21 个检查组全部通过**，覆盖搜索目录、标签与菜单模型、历史/书签存储、网站模式、WebRTC 策略源码边界及权限拒绝声明。RFC 8484 loopback HTTPS mock 的 **37 项检查全部通过**；mock 共收到 9 个本机 HTTPS 请求，外部/Quad9 查询数为 0，且 DoH 错误路径没有明文 DNS 回退。

`:app:lintRelease :app:assembleRelease` 最终构建成功。Lint 为 0 errors、2 warnings：target API 35 的 `OldTargetApi` 提示，以及 Gradle wrapper 9.7.1 可升级至 9.8.0 的版本提示。两个 APK 均通过 ZIP 完整性、ZIP 与所有 native ELF 16 KB 对齐及包内 runtime 静态扫描；只含预期 Gecko native libraries，没有 Cefrium、CEF、Chromium runtime 或 `libcef.so`。签名为 Android debug key，v3 验证为真、v2 为假；不能将本构建描述为同时具有 v2/v3 签名。APK 大小、SHA-256、权限和完整逐包结果见 `本次构建交付目录/reports/apk-verification-final.txt`。

没有可用 Android 设备或模拟器，因此未安装、启动或实际加载网页；没有验证设备上的 Gecko DNS 路径、WebRTC ICE 行为、Experimental 偏好在目标设备上的效果，或 VPN TUN 与 Gecko 的真实配合。没有访问公网 DNS/WebRTC 泄漏检测站，也没有运行公网 STUN/TURN 测试。以上边界应与构建和离线测试结果一并解读。


### GeckoView 内部后台通信

对锁定版 `GeckoRuntimeSettings.Builder` 的公开 API 签名检查未发现可核实的遥测上传 setter。本项目未添加未审计偏好键，也未对 GeckoView 内部遥测/远程配置流量进行网络抓包；因此不声称 GeckoView 不会产生任何后台连接。API 检查和限制见本次构建交付目录中的 `reports/geckoview-telemetry-api-audit.txt`。
