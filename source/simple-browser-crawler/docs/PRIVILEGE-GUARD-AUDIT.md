# Simple Browser 2.6.0 / v0.4 — App-Local Privilege Guard Audit

## 简体中文

### 范围与触发时点

守卫由 `PrivilegeGuardApplication.onCreate()` 在应用进程启动时调用；Activity 每次恢复前再次检查；DNS-only VPN 服务在收到启动命令时也复查。它只检查本应用和当前进程，不扫描其他应用，也不探测整台设备是否 root。守卫不是常驻轮询器：应用仍在运行时若特权状态被外部改变，通常要等到下一次上述检查点才会发现。

检查失败、API 抛错或所需系统状态不可读/不可解析时，守卫拒绝继续启动。严格 fail-closed 有兼容代价：OEM/ROM 对 PackageManager、AppOps、DevicePolicyManager 或 `/proc/self/status` 的限制、字段变化或行为差异可能令应用误报并无法使用；未审阅的新权限也会被拒绝。

### 本应用的检查项

- **进程身份：**拒绝当前本应用 UID 为 root（0）或 shell（2000），并识别 Android 多用户 UID 中 appId 为 shell 的情形。检查对象是本应用进程，不是设备整体 root 状态。
- **有效 Linux capabilities：**从 `/proc/self/status` 读取 `CapEff`、`CapPrm`、`CapInh`、`CapAmb`，要求四项都能解析且为零。任何值非零或读取失败都会阻断启动；不会把 bounding set `CapBnd` 当作当前进程已经拥有的能力。
- **请求权限与危险权限授权：**合并后的权限集合必须与 allowlist 精确相符；逐项核验保护级别和本包授权状态。摄像头、麦克风、粗略定位与精确定位的用户手动授权被保留，守卫不替用户请求授权。
- **特殊 App Ops/特殊访问：**只针对本包 UID/package 查询映射到 overlay、修改系统设置、usage stats、all-files、安装未知应用、exact alarm 等访问的 App Ops；`MODE_ALLOWED` 或 `MODE_FOREGROUND` 会被拒绝。并核对对应平台状态，包括通知策略访问。不会把正常通知或 VPN 能力当作异常特权。
- **设备管理、无障碍和通知监听：**查询本应用是否为 device/profile owner，只核验本包自己的 Device Admin、AccessibilityService 与 NotificationListenerService 状态；不枚举其他应用或设备上的服务。本版本不声明或提供这些额外服务/绑定权限，也不把无法通过普通 App-permissions 页面授予的系统级访问说成普通运行时权限。
- **隔离进程：**如果进程是 isolated process，仍会检查 UID 和 capabilities。只有进程名匹配锁定 GeckoView AAR 的 `:isolatedTab_disable_art_image_` 前缀时，才跳过仅适用于 app UID 的检查；未知隔离进程、异常或依赖 API 不可用均 fail-closed。升级 GeckoView 后须重新审阅该进程命名约定。

### Manifest allowlist 与保留功能

Release merged manifest 的预期权限集合共 11 项：`INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK`、`MODIFY_AUDIO_SETTINGS`、用户手动授予的 `CAMERA`、`RECORD_AUDIO`、`ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION`、用户主动 DNS-only VPN 所需的 `FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`，以及 AndroidX 生成并以 `signature` 保护的 `com.cue.simplebrowser.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。

相机、麦克风和定位授权仍由用户在 Android 设置中管理；缺少授权时相应网页请求会被拒绝。VPN 仍需用户明确触发并通过 Android VPN consent；不会随开机或网页请求自动启动。没有加入广泛媒体/文件权限、overlay、write-settings、manage-all-files、安装未知应用、usage-stats、精确闹钟、Accessibility、Device Admin 或 Notification Listener 绑定权限。

### GeckoView 157 隔离配置与证据边界

源代码显式调用 `.isolatedProcessEnabled(true)`、`.fissionEnabled(true)` 并设置 `GeckoRuntimeSettings.STRATEGY_ISOLATE_EVERYTHING`。Gecko isolated content service 与 Fission 按站点隔离是不同层次；这些设置只证明本候选的配置，不证明设备上实际启动出的进程/站点隔离效果。没有连接 Android 真机或模拟器，因此不声称完成运行时保护验证。

### 明确不提供的保证

守卫不能抵御已取得内核或操作系统控制权的 root/shell、被修改或谎报状态的 Android framework、绕过应用代码的运行时注入，亦不能验证 Android 返回值是否真实。它不持续监视，也不能保证对所有 OEM ROM 均无误报。GeckoView isolated process 与 Fission 不会把每个网站变成独立 Android 应用，也不消除 Gecko 漏洞、IPC 风险或应用主进程本身的权限。

### English

The guard protects only the app's own process and package state. It runs at application-process startup, before an Activity resumes, and when the optional DNS-only VPN service receives a start command. It is fail-closed: an unexpected permission, denied policy check, exception, or unreadable/unparseable required system value prevents the app from continuing. It is not a continuous monitor; a change made while the app stays open is ordinarily detected at the next check point.

Checks cover the app's requested-permission allowlist and dangerous-permission grants; selected special App Ops for overlay, write-settings, usage access, all-files access, package installation, exact alarms, and notification-policy access; this app's device/profile-owner, device-admin, accessibility-service, and notification-listener state; and the current process UID and Linux capability fields. Root UID 0, shell UID 2000 (including a multi-user shell appId), and any nonzero or unreadable `CapEff`, `CapPrm`, `CapInh`, or `CapAmb` are rejected. The guard does not treat `CapBnd` as an effective capability.

The merged allowlist retains manual camera, microphone, and coarse/fine location permissions, plus the user-triggered DNS-only VPN. It does not request broad media/file access or add device-admin, accessibility, or notification-listener services. Some checks concern privileged system access that cannot be granted as an ordinary runtime permission; the app does not claim otherwise.

This is **not whole-device root detection** and does not defend against an attacker who already controls the kernel/OS, modified or deceptive Android APIs, or runtime injection. Strict fail-closed checks can also cause false positives or prevent use on OEM builds that restrict or vary system APIs. GeckoView 157 isolated content-process and all-site Fission settings are explicit configuration-level defense-in-depth only. No connected device or emulator was available, so no runtime isolation or protection result is claimed.
