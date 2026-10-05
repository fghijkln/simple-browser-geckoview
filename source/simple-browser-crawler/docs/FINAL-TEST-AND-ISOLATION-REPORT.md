# 简浏览 2.3.0 构建与环境隔离报告

**日期：** 2026-10-05  
**Application ID：** `com.cue.simplebrowser`  
**版本：** `2.3.0` / `versionCode 12`  
**GeckoView：** `157.0.20260924084938`

## 实现摘要

- 以 `/workspace/simple-browser-geckoview/` 为只读基线，创建独立副本 `/workspace/simple-browser-fingerprint/`；构建输出全部在 `/workspace/simple-browser-fingerprint-build/`。
- 增加 Profile Manager：创建默认/多个环境、切换、改名、查看配置摘要、删除双确认；主界面标识当前环境。
- Profile 数据目录位于 `filesDir/fingerprint-profiles/<random UUID>/`，名称不参与路径；管理 JSON 采用 schema v1、临时文件 + fsync + rename、备份副本、`deleting` tombstone、UUID path 验证与无符号链接跟随的目录清理。历史/书签/站点 UA mode 使用 `browser.profile.<UUID>` 私有偏好；切换前同步提交本地状态。
- 切换通过关闭并 flush tabs/sessions、shutdown 当前 Runtime，再由独立 app process 等待旧进程退出后启动新 Runtime。新 session 使用官方 Builder 的通用 `arguments(...)` 注入 `-profile <UUID path>`。
- GeckoView 157 没有专用公开 Java 多 Profile API。Mozilla 官方 API 证据及 `-profile` 参数的验证边界见 [`GECKOVIEW-PROFILE-API-AUDIT.md`](GECKOVIEW-PROFILE-API-AUDIT.md)。
- 每个环境显示 GeckoView 移动 UA 默认模板，以及创建时系统 locale/timezone 快照；这些是来源透明的模板/快照，不修改或伪造硬件参数。所有环境仍共享同一 Android 设备与 GeckoView 引擎；硬件、OS/build、图形、屏幕/字体等特征可能相同。无反自动化/反欺诈绕过功能。
- Android manifest `allowBackup=false`，cloud backup 与 device transfer rules 排除 app-private 数据。Profile 删除先写 tombstone，之后清理 UUID 偏好和 Gecko 目录；异常时保留 tombstone 供后续重试。
- 502 项搜索目录、书签/下载实现、严格跟踪防护、HTTPS-only、Quad9 DoH-only、无媒体权限和 WebRTC Experimental 保护保留。VPN UI 区分 Android 系统授权、服务状态与有效 TUN；它仍不证明 Gecko DNS 实际经过隧道。

## 旧版数据策略

不把 2.2.0 的默认 Gecko profile 自动复制或拆分到多个环境，也不删除旧数据。新建 profile 从独立 UUID 路径启动。旧版全局 history/bookmark/站点模式偏好未迁移到新 profile，因此旧记录不会出现在新环境列表中，但本次代码也没有清空原 app-private 文件/偏好。下载仍按既有路径使用 Android “下载/Simple Browser”。当前没有导入/迁移 UI。

## 构建与 lint

实际构建环境：OpenJDK 21.0.12、Gradle Wrapper 9.7.1、Android Gradle Plugin 9.4.0、Android SDK platform `android-37.2` 与 Build Tools 37.0.0。执行 `lintRelease assembleRelease`，最终重跑 **BUILD SUCCESSFUL / exit 0**。

`lintRelease`：**0 errors、1 warning**。唯一 warning 是现有 `targetSdk 35` 低于当前 lint 数据库建议的最新 Android target（`OldTargetApi`）；新增 Profile Manager 字符串警告已通过资源占位符修复。Gradle 还报告项目使用将与 Gradle 10 不兼容的弃用功能，Java 编译器报告 MainActivity 使用 deprecated API；这些未阻断当前 release 构建。

## Release APK 校验

两个 APK 均由 `aapt` 校验 package/version/ABI，`apksigner verify` 通过，`zipalign` 的标准 4-byte 检查与 `-P 16` 16-KB 页面对齐检查都通过。Release build 使用 Android **debug certificate**，不是 Play Store/production 签名：v3 签名为 true，v1/v2/v3.1/v3.2/v4 均为 false。

| ABI | APK | 大小 | SHA-256 |
|---|---|---:|---|
| arm64-v8a | `/workspace/simple-browser-fingerprint-build/gradle/app/outputs/apk/release/app-arm64-v8a-release.apk` | 224,709,505 bytes | `05fc2bbff1f5c5d4a9468b11ead2cb8d451c587c54f05c082dcad57f00a083d1` |
| x86_64 | `/workspace/simple-browser-fingerprint-build/gradle/app/outputs/apk/release/app-x86_64-release.apk` | 244,072,858 bytes | `d4d3bd462ec5f267b2abaeaee99a245c01d591e9470df06469d28305cf6adf85` |

DEX/native ELF 与 APK entry-name marker 检查未发现 Chromium/CEF 条目、descriptor 或 `libcef` 标记。每个 APK 含 13 个 Gecko/Mozilla native 库（包含 `libxul.so`、`libmozglue.so`）；这些是预期 Gecko runtime，不是 Chromium/CEF。

## 自动化测试

- `bash tools/run-geckoview-local-tests.sh`：最终 **29 PASS**，覆盖既有搜索目录/书签/历史/标签页/壁纸/扩展只读流程、ProfileStore schema/fallback、两 UUID 私有目录区分、路径注入拒绝、rename 不改路径、待删除 tombstone 重启恢复、递归删除、至少一个 profile 约束与 orphan 创建恢复。
- `bash tools/run-dns-doh-local-test.sh`：最终 **37 PASS**；RFC 8484 loopback mock 验证 DoH 失败不回退明文 DNS、IPv4/IPv6/TCP、证书验证及 VPN 路由限制；明文 DNS fallback attempts = 0，外部/Quad9 查询 = 0，mock HTTPS 请求 9 次。
- APK verification 工具：`tools/verify_release_apks.py`，报告与哈希保存在 `/workspace/simple-browser-fingerprint-build/reports/apk-verification.txt`。
- Gradle 输出：`/workspace/simple-browser-fingerprint-build/reports/gradle-release-build-final.log`；lint HTML/SARIF/TXT 位于 `/workspace/simple-browser-fingerprint-build/gradle/app/reports/`。最终两组本地测试日志位于 build root `/workspace/simple-browser-fingerprint-build/reports/`。

## 尚未验证与用户可见限制

当前 Sandbox 有 Android SDK/adb，但 `adb devices -l` 无设备，未配置 AVD，且未找到 emulator binary。因此此次是 **build-only + offline tests**：没有安装/启动 APK，没有真机切换 Runtime，也没有核对两个 Gecko profile 中真实生成的 `cookies.sqlite`、cache、site storage/permission 数据路径。官方文档只证明 `arguments()` 传到 Gecko 主进程，并不证明每台设备上的 `-profile` 行为，必须在 Android 设备上验证后才能把浏览器数据隔离视为端到端验收。Profile Manager 配置摘要已明确显示此边界。

其他明确限制：旧版默认数据没有导入 UI；profile 仅隔离本地状态，不让 Android 硬件特征变成不同设备；locale/timezone 当前是可查看来源快照，不是强制浏览器参数；WebRTC protection 仍使用 Experimental Gecko preference，未作 IP/ICE 零泄漏保证；VPN 已授权/实际 TUN 连接也不等于 Gecko DNS 路径验证。
