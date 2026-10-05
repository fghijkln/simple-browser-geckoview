# Simple Browser v0.6 本地候选报告

**应用：** `com.cue.simplebrowser` · **版本：** 2.9.0 · **versionCode：** 19  
**公开源码基线：** [`v0.5` tag，commit `26a28a72d10a7907e8a995c8f2cf03568765af5d`](https://github.com/fghijkln/simple-browser-geckoview/tree/26a28a72d10a7907e8a995c8f2cf03568765af5d)。工作副本位于全新隔离目录 `/workspace/simple-browser-unrestricted-v0.6/`；基线 `HEAD` 与 tag 均未改动，也未修改此前工作目录。**没有创建或发布 GitHub branch/tag/release。**

## 本次“放宽”的准确含义

此前列出的应用阻断和上限已改为警告/用户选择，或在设置中提供有限值与明确的 `0 = 不限` 档。此处“不限”仅表示移除对应的**应用配置上限**，不代表 Android/Java 可以提供无限内存、无限长度或永不失败。本报告不把平台/运行时边界写成无限，也不声称 2.9.0 已真机验证。

## 实现矩阵

| 项目 | v0.6 实现 | 保留边界 / 注意事项 |
|---|---|---|
| 特权守卫 | 应用权限、额外能力/AppOp、状态不可读及环境发现汇总为启动告警；用户明确选择“继续/退出”。设置可重看说明。 | 普通守卫检查异常不再单独导致浏览器崩溃；应用上下文损坏、真实崩溃或无法安全初始化仍按 fatal 路径失败。其他 app-own 权限/能力/AppOp、设备管理、无障碍、通知侦听等检查仍在。仅源码回归，未真机注入异常。 |
| 风险/免责声明 | 每次新启动展示简中/英文说明；列出守卫发现，要求继续或退出，设置可重看。说明写明“此说明提醒风险，不改变法定责任/授权状态”。 | 真机弹窗布局与生命周期未验证。免责声明不代表用户具有网站访问授权。 |
| USB/ADB DevTools | 默认启用，可在设置关闭；Runtime endpoint 仍通过 GeckoView API 创建。 | 不启用 Wi-Fi/LAN listener，不新增 Android 权限。真实设备端点发现/连接未验证。 |
| 页内 Console | 默认开放；显示标量 Console 参数、错误/rejection 正文、stack/path；原始日志内容可能含凭据或个人数据。 | 安装前仍明确提示 HTTP(S) 全站 host permission。只处理选中 GeckoSession 的顶层页、面板开启期间；日志仅内存、可清空，关闭面板清除并卸载扩展。没有另读 cookies、DOM/表单、请求/响应正文、墙钟时间；不添加通用 JS Bridge/native eval。网页可伪造日志。 |
| Console 容量/速率 | 行数：500/1,000/2,500/5,000/不限，默认 5,000；单条及单参数正文：16,384/65,536 UTF-16 代码单元/不限，默认 16,384；速率：15/60/120/不限条每秒，默认 60。 | 不限选项有风险确认；有限字符值作用于合并日志及单参数。参数数量不再有固定 64 条应用封顶。不限仍受 Java 集合、整数/字符串表示、进程堆和 Android 系统分配限制，可能 OOM/ANR/崩溃。 |
| CAMERA/MIC/location | 网页请求时应用调用 Android runtime permission 请求；授予后仍逐网站确认，拒绝任一步骤则拒绝网站请求。 | 只使用原 Manifest allowlist 已有的 CAMERA、RECORD_AUDIO、ACCESS_COARSE_LOCATION、ACCESS_FINE_LOCATION；没有新增权限。真实系统弹窗/approximate location 未验证。 |
| 抓取执行 | 用户显式启动；前台串行请求；每次运行前告知可能的大量站点流量、拒绝/封锁、违反站点条款或增加第三方负载，以及内存风险。 | 不用浏览器 Cookie/session、登录态、代理或伪装；只解析服务器实际响应的静态 HTML/纯文本。运行 Android Java HTTP(S)/系统 DNS，不继承 GeckoView 的 Quad9 TRR-only/DNS-only VPN 路径。 |
| 认证/挑战页 | CAPTCHA、登录墙、付费墙及 401/403 可由用户选择仅查看服务器本次实际返回的静态提示，或在浏览器手动打开并停止抓取。 | 不自动登录、不破解 CAPTCHA、不跟进提示页链接或绕过控制；抓取器不共享浏览器 Cookie/session，因此不能抓取需认证内容。仅展示服务器实际响应，不宣称取得受保护正文。 |

## 抓取器设置

| 设置 | 默认 | `0 = 不限` / 用户选项 |
|---|---:|---|
| 页面数 | 100 | 不设应用页数上限 |
| 待处理 URL 队列 | 10,000 | 不设应用队列数量上限 |
| 总时长 | 30 分钟 | 不设应用总时长上限 |
| 单页响应正文 | 16 MiB | 不设应用单页字节上限 |
| robots.txt 响应正文 | 512 KiB | 不设 robots 正文字节上限，含其重定向目标 |
| 同源重定向 | 3 跳 | 不设跳数上限；精确 URI 环路仍会停止 |
| 额外最小请求间隔 | 1 秒 | 不加应用额外间隔；相应模式下仍尊重 robots `Crawl-delay` |
| 429/503 应用额外等待 | 30 秒 | 不加应用额外等待；仍遵守服务器 `Retry-After` |
| robots 策略 | 遵守 | 遵守、对每个禁止路径逐条询问或忽略 |
| 跨站站点/重定向 | 关闭 | 用户开启后逐个确认新站点/跨站重定向 |
| 普通非成功响应 | 逐响应提示 | 用户可关闭询问或在提示后继续其他排队 URL；429/503 重试仍须逐次确认 |

`429/503` 不会自行高速重试；每次重试均由用户再次确认。选 0 秒应用等待不会绕过服务器 `Retry-After`，但在服务器没有要求等待时，用户确认后可立即进行下一次请求。抓取没有后台模式、并发洪泛、自动重试风暴、验证码破解、绕过登录/付费墙、Cookie/session 窃取、IP 轮换、代理欺骗、UA/TLS/fingerprint 伪装。

## 明示保留的技术边界

以下是准确的残余边界，不是可取消的设置值，也不应误读成“无限抓取/无限日志”：

1. **Android/Java 资源上限：** 单页响应经 Java byte array/String 处理；队列、页面结果和 Console 条目驻留进程内存。Java 整数/字符串索引、集合实现、Android heap、系统分配与可用内存均有客观限制。`0` 移除对应应用配置上限后，仍可能 OOM、ANR、崩溃或变慢。抓取计数/跳数 UI 使用有符号 `int`，时长/字节/等待使用 `long` 并做溢出检查。
2. **网络超时：** crawler 仍保留固定 8 秒连接超时和 8 秒读取超时（未提供可关闭选项）；慢服务器/慢响应仍可能失败或被取消。这不是页面/总时长/单页字节设置的替代品。
3. **结果摘要展示：** 每页可见文字摘要仍限制约 12,000 字符；纯文本摘要展示首段，HTML 的正文摘要截短，但 HTTP 响应仍按用户选择的单页字节配置读取，HTML 链接扫描继续至完整响应末尾。摘要上限不等于抓取响应体限制。
4. **无限 robots 正文/跳转：** robots 读取仍受 Java/Android 内存边界；同源重定向设为 0 时精确 URI 环路检测仍会停止，避免同一地址循环请求。进程内 robots 规则缓存保留最多 32 个站点、5 分钟；逐出只会令后续重新读取 robots.txt，不限制可访问站点或抓取数量。
5. **保留的目标地址策略：** 用户可选择正常跨站站点/重定向，但仍只允许 HTTPS/443，并在请求前检查解析地址拒绝本机/私有/特殊用途地址。此处是主机名预检，不是连接时 DNS pinning；DNS 在预检和连接间变化的情形不能据此排除。本任务没有新增 VPN/网络白名单。
6. **请求串行/用户取消：** crawler 始终串行并可由用户取消；robots `Crawl-delay` 与服务器 `Retry-After` 按策略等待。这些不是自动高速重试。

因此本候选实现了指定的“用户可选有限值或不限”，但**不声称实际资源无限，也不声称所有网络/系统约束都可关闭**。

## v0.5 profile 兼容和回退

- 原地升级保留浏览器 profile、历史、书签、站点权限、Cookie/profile 隔离和用户私有数据路径；不执行破坏性迁移。
- 未曾显式设置的旧 profile 调试偏好采用 v0.6 默认开放值；已有明确的用户开关选择继续保留。抓取参数缺省时用上表默认值。新启动显示说明。
- 没有新增 Manifest 权限；没有改 DNS/ETP/WebRTC 默认、Cookie 隔离或 VPN/网络白名单。英文翻译仅覆盖启动风险说明和本次相关 UI；其他旧 UI 可能继续显示简中。
- 回退前备份数据。Android 通常不允许直接覆盖降级；卸载后重装 v0.5 会删除应用私有数据。APK 由 v0.5 相同的 Android debug 证书签名，不是公众发行/商店签名证书。

## 回归、构建与静态审计

- `bash tools/run-crawler-local-mock.sh` **通过**：loopback-only 模拟覆盖默认值、0=不限页数/队列/单页字节/robots 正文字节/同源重定向/等待；超过默认 100 页、16 MiB、512 KiB、3 跳；robots 重定向正文不限、链接扫描超过摘要边界、重定向环路停止、跨站提示、robots 遵守/忽略/逐条询问、普通 404 继续其他排队页、挑战/403 仅显示真实响应、不跟随提示页、429/503 逐次确认/取消、无 Cookie、串行和取消。
- `bash tools/run-geckoview-local-tests.sh` **通过**：既有 app/profile/UI/权限/WebRTC/ETP 回归，以及 fail-open 启动说明、Android runtime permission allowlist + 逐站确认、Console 有限/不限日志文字/参数数/行数/速率/清空/内存边界检查。
- 最终 `./gradlew --offline --no-daemon :app:lintRelease :app:assembleRelease --console plain` **BUILD SUCCESSFUL**。Lint **0 errors、6 warnings**：`OldTargetApi`（targetSdk 35）、3 个 `PluralsCandidate`（英文数量文案）、`ObsoleteSdkInt`（既存 API 检查）和 `UnusedResources`（旧权限提示资源）。未通过 baseline 隐藏这些 warning；仅对英文局部覆盖未包含的旧界面使用文件级 `MissingTranslation` 忽略。
- 两 ABI APK 均是 `com.cue.simplebrowser`、2.9.0/code19、minSdk29/targetSdk35/compileSdk37；arm64 包只含 arm64 native libraries，x86_64 包只含 x86_64 native libraries。
- arm64 与 x86_64 Release merged Manifest 均为 11 项权限，与 v0.5 2.8.1 参考 APK 权限列表逐项相同、diff 为空。Camera、autofocus、microphone、location/GPS features 为 `required=false`。Release `application` 未设置 `android:debuggable`（Android 默认 false）。详见 [Manifest 审计](MANIFEST-AUDIT-v0.6.txt)。
- 两 APK 均通过 APK Signature Scheme v3；证书 SHA-256：`e82931a4c130f749fe03fa6f6b530007d03a7b00f003aea47cdc3d0f69e28dfd`，与 v0.5 参考 APK 相同。ZIP 完整性与 `zipalign -c -P 16 -v 4` 均通过。

## 本地交付物

| 文件 | 说明 |
|---|---|
| `release-artifacts/SimpleBrowser-2.9.0-arm64-v8a.apk` | arm64-v8a Release APK；218,827,115 bytes |
| `release-artifacts/SimpleBrowser-2.9.0-x86_64.apk` | x86_64 Release APK；238,190,468 bytes |
| `release-artifacts/SimpleBrowser-2.9.0-source.zip` | 基于指定 v0.5 提交的完整源码/测试快照，不含 Git 对象、APK 或构建缓存 |
| `RELEASE-NOTES-v0.6-zh-CN.md` / `RELEASE-NOTES-v0.6-en.md` | 双语发行说明草案 |
| `MANIFEST-AUDIT-v0.6.txt` | merged Manifest/权限/features/签名/ABI/ZIP 审计 |
| `BUILD-2.9.0-final-followup.log` | 最终双 ABI构建与 lintRelease 输出 |
| `LINT-REPORT-2.9.0-final.txt` | 最终完整 lint issue 报告（0 errors，6 warnings） |
| `SHA256SUMS.txt` | 双 ABI APK、源码 ZIP、说明、报告、审计和 build/lint 证据哈希 |

## 未完成/未验证

没有 Android 真机安装或运行测试；未验证真实相机/麦克风/位置系统弹窗、approximate/precise location、ADB DevTools endpoint、Gecko WebExtension host-permission UI、Runtime 重启导航历史、真实网站 429/robots/挑战行为。离线 crawler 测试仅为 loopback 模拟，不代表真实网站授权/负载合规。候选仍为本地文件，未远端发布。
