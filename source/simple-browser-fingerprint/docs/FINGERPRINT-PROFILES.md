# 浏览环境与隐私隔离（2.3.0 / versionCode 12）

## 范围与命名

本功能把“环境”定义为本地浏览资料隔离区，不承诺不可检测、硬件身份不同、匿名或反欺诈/反机器人绕过。UI 将其显示为“浏览环境”，而不是宣称独立设备指纹。每个环境可新建、切换、改名、查看配置摘要和双重确认删除；主界面持续标出当前环境。

## GeckoView 与运行时

GeckoView 157 没有被官方公开为多 profile Java API。Runtime 初始化参数可使用官方 `GeckoRuntimeSettings.Builder.arguments(String[])` 传入通用 Gecko 参数，但 Mozilla 文档没有声明一个 dedicated Java profile setter。实现为单进程/单 Runtime：GeckoRuntime 启动时收到 `-profile <app-private-UUID-directory>`。切换会先同步 profile 级 SharedPreferences，flush/关闭 sessions、shutdown Runtime；独立 `:profile_restart` 协调 Activity 等旧 app 进程退出，再从新进程创建新 Runtime/session。完整 API 依据与限制见 [GECKOVIEW-PROFILE-API-AUDIT.md](GECKOVIEW-PROFILE-API-AUDIT.md)。

## 私有存储

- Gecko profile 根：`filesDir/fingerprint-profiles/<random UUID>/`。
- 管理元数据：`filesDir/profile-manager/profiles.json`；临时写入与崩溃恢复备份也位于相同 app-private 目录。
- 用户可编辑名称从不参加文件路径计算； profile ID 必须是标准 UUID，path traversal 名称/ID 被拒绝。
- Profile history、书签、该 profile 的手机/桌面 UA 请求模式保存在 `browser.profile.<UUID>` app-private SharedPreferences。删除前用 UUID 找到并同步清除。其他浏览器数据由 Gecko 的 profile 参数指向每个 UUID 目录。
- Android Manifest 的 `allowBackup=false`，full backup 与 Android 12+ cloud/device-transfer extraction 规则全部排除 root/files/database/sharedpref/external 数据。环境数据不会由 app 备份或跨设备迁移；卸载 app 也会移除其私有资料。
- 下载仍按已有功能存放到 Android “下载/Simple Browser”；它不是 profile 私有 Gecko 状态，也不会被 profile 删除。

## Metadata schema 与事务

Schema 版本为 `1`。每条记录包含 UUID、显示名、创建时间、UA 模板说明、locale 与时区系统快照、来源说明和状态；当前 profile ID 单独记录。所有 JSON 写入走 UTF-8 临时文件、fsync 与原子 rename；保留可回退的 metadata copy。创建若提交失败会清除新 UUID 目录；启动时清理未提交的 UUID orphan。metadata 不可读时会在 app-private 管理目录隔离损坏副本、建立可用默认环境，并保留未知旧 profile 数据，而不会把未识别目录当作垃圾删除。

删除分为两阶段：先持久化 `deleting` tombstone，启动新会话时先同步清理对应 UUID SharedPreferences，再递归删除 Gecko UUID 目录，最后移除 metadata；中途失败的 tombstone 留存并重试。目录遍历不跟随符号链接。当前环境删除前必须已有替代环境，删除确认分两次；系统至少保留一个 active profile。profile 路径下浏览历史、Cookie、cache、site storage、站点权限等目录若确由 Gecko 写入，递归删除 profile directory 会一并删除。

## 数据策略与现有资料

更新不会把 2.2.0 的旧 Gecko 默认 profile 或旧 app-wide history/bookmarks 自动迁移到任一新环境，也不会为创建环境而删除旧数据。新版本新建“默认环境”；旧默认数据仍作为 app 原有私有资料留在原路径/旧偏好项，避免静默覆盖、丢失或混入多个环境。用户需自行决定是否继续保留旧资料；当前无迁移 UI。

搜索引擎目录及其 502 项、DNS/ETP/WebRTC 策略、书签与下载功能都保留；新写入的浏览历史/书签与 UA 站点模式按 profile 分开保存。删除环境会清掉新版该 UUID 自己的 history/bookmark/site-mode preferences。

## 模板与共享设备特征

默认 UA 模板是 GeckoView 移动 UA；不伪造硬件字段。创建时记录 Android `Locale`/时区快照，来源清楚并标注为 informational template，不通过 `navigator` 或 Gecko 假定接口强制伪装 Locale/时区。手机/桌面请求 UA 模式为每 profile 单独存储并且是用户可选的现有功能。

所有环境共享相同 Android 设备与安装的 GeckoView 引擎：Android OS/build、Gecko 版本、硬件/图形、屏幕/字体和其他引擎可见特征可能相同。各环境继续使用严格 ETP、HTTPS-only、Quad9 TRR-only、无媒体权限和 WebRTC protection experimental preference；可选 DNS-only VPN 只有系统授权且 TUN 有效时才报告连接。连接状态不证明 Gecko 的解析请求实际走 VPN/Quad9；本轮无 Android 设备测到 DNS 解析路径。WebRTC preference 保持 Experimental，未宣称完全无 ICE/IP 暴露。

## 验证边界

离线 Java 测试检查两条私有目录路径互异、UUID/名称不可路径注入、默认环境 fallback、schema metadata、待删标记恢复、目录递归删除和 orphan 创建恢复。DoH/VPN local mock 测试仅走 loopback，无公网 Quad9 查询。

本构建机存在 Android SDK/adb，但未连接 Android 设备，也没有安装 Emulator/AVD。因此尚未运行 APK、切换环境或用两个真实 Gecko Runtime profile 检查 cookies.sqlite、cache、site-storage/permission 目录路径；在设备验证完成之前，代码侧 UUID 目录隔离不应被理解为已验证的 Gecko 数据隔离。构建、lint 与 APK 静态检查的结果在交付报告中单列。
