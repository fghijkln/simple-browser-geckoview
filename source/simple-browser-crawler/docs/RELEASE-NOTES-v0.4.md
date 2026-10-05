## 简体中文

**简浏览 v0.4**（Android 应用 **2.6.0**，`versionCode 15`）基于 Mozilla GeckoView Stable `157.0.20260924084938`，提供 `arm64-v8a` 与 `x86_64` 两个 ABI APK；不提供通用 APK。

本版增加 fail-closed 的**应用自身特权守卫**。它只检查本应用：merged manifest 中新增或未列入 allowlist 的权限、危险权限授权、特定特殊 App Ops、设备/档案所有者及本应用的 Device Admin、无障碍、通知监听器状态；还检查本进程 UID 是否为 root（0）或 shell（2000），以及 `CapEff`、`CapPrm`、`CapInh`、`CapAmb` 是否可读且全为零。任一检查失败，或所需系统状态无法读取/解析，应用停止继续启动。

守卫保留用户手动批准的摄像头、麦克风、粗略/精确定位权限，也保留**仅在用户明确操作并通过 Android VPN consent 后启动**的 DNS-only VPN。照片、上传文件和扩展 ZIP 仍通过 Android 系统文档选择器；没有增加广泛媒体或文件权限。

守卫**不检测整台设备是否 root**，也不能保证抵御已被 root/攻击者或操作系统控制、被修改或谎报状态的系统 API，或运行时注入。它不是后台持续监视器：检查发生在应用进程启动、Activity 恢复前及 VPN 服务收到启动命令时，运行期间外部状态变化通常到下一检查点才会发现。部分设备管理/无障碍/通知监听等是特殊系统访问，并非普通应用权限页中的运行时权限；本版不新增相应服务或声称能通过普通设置授予它们。严格 fail-closed 策略可能因 OEM/ROM 的 API 限制或差异而误报、阻止启动；未审阅的新权限也会被拒绝。

GeckoView 157 的 isolated content process、全站点 Fission（`STRATEGY_ISOLATE_EVERYTHING`）是显式的**配置级纵深防御**。没有连接 Android 真机或模拟器，因此未验证其运行时进程隔离、站点隔离或守卫效果；构建和静态检查不等于真机保护测试。

**签名：**Release 使用项目现有 Android Debug key，而不是应用商店生产签名。证书 SHA-256 `e82931a4c130f749fe03fa6f6b530007d03a7b00f003aea47cdc3d0f69e28dfd` 与 v0.3 的两个 APK 一致。安装升级要求设备上已有版本使用相同证书。

随附构建报告记录 lint、回归测试、Release APK 签名/ZIP/对齐/manifest 与哈希核验及未验证边界。源码 ZIP 包含应用源码、测试、权限与安全审计，以及中英文 README。

## English

**Simple Browser v0.4** (Android app **2.6.0**, `versionCode 15`) uses Mozilla GeckoView Stable `157.0.20260924084938`. ABI-specific `arm64-v8a` and `x86_64` APKs are provided; there is no universal APK.

This version adds a fail-closed **app-local privilege guard**. It checks only this app: new or non-allowlisted permissions in the merged manifest, dangerous-permission grants, selected special App Ops, device/profile-owner state, and this app's device-admin, accessibility, and notification-listener state. It also rejects a process UID of root (0) or shell (2000), and requires `CapEff`, `CapPrm`, `CapInh`, and `CapAmb` to be readable and zero. If a check fails or required system state cannot be read or parsed, the app does not continue starting.

User-approved manual camera, microphone, and coarse/fine location permissions remain available. The optional DNS-only VPN is retained and starts only after an explicit user action and Android's VPN consent. Photo selection, file uploads, and extension ZIP import continue to use Android's system document picker; no broad media or file permission was added.

The guard **does not detect whether the whole device is rooted** and cannot guarantee protection against a kernel/OS already controlled by an attacker, modified or deceptive system APIs, or runtime injection. It is not a continuous background monitor: checks run at app-process startup, before an Activity resumes, and when the VPN service receives a start command; a change made while the app stays open is ordinarily detected at the next check point. Some device-management, accessibility, or notification-listener access is special system access, not an ordinary runtime permission; this release adds no such service and does not claim it can be granted from the normal app-permissions screen. Strict fail-closed behavior may produce false positives or prevent startup on OEM/ROM builds that restrict or vary the required APIs; unreviewed permissions are rejected.

GeckoView 157's isolated content process and all-site Fission (`STRATEGY_ISOLATE_EVERYTHING`) are explicit **configuration-level defense-in-depth**. No Android device or emulator was connected, so runtime process isolation, site isolation, and guard behavior were not verified on-device. Compilation and static checks are not a real-device protection test.

**Signing:** Release uses the existing Android Debug key, not a production app-store key. Its certificate SHA-256 `e82931a4c130f749fe03fa6f6b530007d03a7b00f003aea47cdc3d0f69e28dfd` matches both v0.3 APKs. An in-place upgrade requires the installed build to use the same certificate.

The accompanying build report records lint, regression tests, Release APK signature/ZIP/alignment/manifest/hash checks, and verification limits. The source ZIP includes app source, tests, permission/security audits, and Chinese and English READMEs.
