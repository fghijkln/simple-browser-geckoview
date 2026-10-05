# GeckoView 157 profile/runtime API 核验

核验对象为项目锁定的 Mozilla GeckoView Stable AAR `org.mozilla.geckoview:geckoview:157.0.20260924084938`，公开 Javadoc 在 2026-10-05 检查。Javadoc 首页当前列出的 API 为 GeckoView 157.0a1；项目本地缓存的 stable AAR 还以 `javap -public` 检查了实际打包的公开类签名。

## 可核实的公开能力

`GeckoRuntime.create(Context, GeckoRuntimeSettings)` 会创建并初始化 Gecko runtime。Mozilla 的 `GeckoRuntime` 文档说明，已有活动 Gecko 实例时再次创建会失败；`shutdown()` 会使所有已附加 session 失效。Mozilla Quick Start 进一步将典型集成描述为“每个进程只初始化一次 GeckoRuntime”。因此，环境切换不能在同一 app 进程内简单重建第二个 runtime，必须先保存/关闭 session，并启动新 app 进程。

`GeckoRuntimeSettings.Builder.arguments(String[])` 是公开 API，文档只承诺设置“custom Gecko process arguments”。GeckoRuntime 157 源码将这些参数从 `settings.getArguments()` 传给 `GeckoThread.InitInfo`；`GeckoThread` 再将它们附加到 Gecko 主进程参数。此接口没有声明 profile 目录语义。

对项目锁定 AAR 的公开类签名检查未发现公开 `GeckoProfile`、profile-name setter 或 profile-directory setter。公开 `GeckoRuntimeSettings.Builder` 只有通用 `arguments(...)`，没有 `profileDirectory(...)`、`profileName(...)` 或等效的、明确声明的数据目录隔离 API。

## 本项目采用的受限启动方式

为每个环境在 Android app 的 internal `filesDir/fingerprint-profiles/<random UUID>/` 下创建唯一目录，并通过官方公开的 `arguments(new String[]{"-profile", absolutePath})` 将 Gecko 命令行 profile 参数传给主进程。目录只由 UUID 生成；可编辑的显示名称不参与任何路径拼接。一次只启动一个 GeckoRuntime；切换由独立的协调进程等待旧进程退出后重新启动主 Activity。

这是一种利用通用参数接口传入 Gecko `-profile` 启动选项的实现，不是 GeckoView 官方公开的多 Profile Java API。Quick Start、Javadoc 与源码可确认参数会被传到 Gecko 主进程，但不能单独证明特定 Android 设备上 Cookie、缓存、history、site storage 和 permission 数据都实际落在指定目录。因此本构建的 profile 文件系统路径隔离仍须在 Android 设备上验证；没有真实 GeckoRuntime 目录读写证据时，不把纯 Java 两目录测试描述成引擎数据隔离验收。

另有 GeckoView/Android 引擎版本、Android 系统构建、设备硬件、屏幕、字体和可见网络特征等可能共享；profile 数据隔离不等于硬件指纹不同。UA/locale/timezone 元数据只记录 GeckoView mobile 默认模板及创建时 Android 系统 locale/timezone 快照，不伪造硬件，也不作反自动化或反欺诈绕过。

## Mozilla 官方来源

- [GeckoRuntime Javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntime.html)：`create`、活动 Gecko 实例限制、`shutdown` 与 session 生命周期。
- [GeckoRuntimeSettings.Builder Javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.Builder.html)：通用 `arguments(String[])` API；文档并未把它定义成专用 profile API。
- [GeckoView Quick Start](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/geckoview-quick-start.html)：示例明确指出 GeckoRuntime 在每个进程只能初始化一次。
- [Mozilla GeckoRuntime.java 源码](https://searchfox.org/mozilla-central/source/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/GeckoRuntime.java)：从 RuntimeSettings 读取 `getArguments()` 并传入 GeckoThread 初始化信息。
- [Mozilla GeckoThread.java 源码](https://searchfox.org/mozilla-central/source/mobile/android/geckoview/src/main/java/org/mozilla/gecko/GeckoThread.java)：把自定义参数追加到 Gecko 主进程命令行。
- [GeckoRuntimeSettings Javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.html)：runtime 网络与隐私配置入口。
