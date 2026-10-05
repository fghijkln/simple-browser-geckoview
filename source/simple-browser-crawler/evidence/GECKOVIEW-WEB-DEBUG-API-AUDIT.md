# GeckoView 157 网页调试 API 核验

## 公开 API 与脚本执行世界

本项目缓存的 GeckoView 依赖为 `org.mozilla.geckoview:geckoview:157.0.20260924084938`。AAR `classes.jar` 的 `javap` 确认：`GeckoSession.ContentDelegate` 没有网页 console/error 回调；`GeckoRuntimeSettings.Builder.consoleOutput(boolean)` 只把 Web console 输出送到 Android logcat，不适合作为只在内存、可清理的应用内日志通道。它继续保持 `false`。

此前对“没有 console callback”的判断不完整。Firefox 128 起，静态 manifest `content_scripts` 支持 `world: "MAIN"`；Mozilla 的[Firefox 128 扩展公告](https://blog.mozilla.org/addons/2024/07/10/manifest-v3-updates-landed-in-firefox-128/)明确说，manifest 声明的 MAIN-world content script 在网页执行环境中运行，不会被严格页面 CSP 阻止，且该脚本不具备 WebExtension APIs。GeckoView 157 使用的 Gecko 版本高于 128。MDN 的 [`content_scripts` manifest 说明](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/content_scripts)同样区分 MAIN 与 ISOLATED：MAIN 与网页共享全局，网页能够读取、访问或修改其逻辑。必须把页面控制的输入视为不可信；不能在该上下文放置秘密、权限或原生操作。

因此，受支持的路径是：console 授权开关只记录 consent；只有用户随后执行一次性的“Open panel”操作才安装内建 WebExtension；在 MAIN world 观察当前主 frame 的 console/error events；由分离的 ISOLATED content script 经 `runtime.connectNative` / `nativeMessagingFromContent` 向 GeckoView 原生 `MessageDelegate` 发送固定 metadata。记录仅包含协议常量 `type=entry`、allowlisted category/level、受限 argumentCount；页面提供的 console 参数、错误正文/rejection reason、stack、文件路径、行号、URL、DOM 或网络数据均不读取或转发，也没有 wall-clock timestamp。原生 `WebConsoleEntry` 只持有 enum、参数数量和本地顺序号。它不通过 DOM `<script>` 标签、`evaluateJS` 或 `window.eval` 注入，也不依赖网页放宽 CSP。

## 消息身份、标签与 frame

GeckoView 的[官方 WebExtensions consumer guide](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/web-extensions.html)说明内建扩展置于 APK assets，通过 `WebExtensionController.installBuiltIn` / `ensureBuiltIn` 安装；`GeckoSession` 的 `WebExtension.SessionController.setMessageDelegate` 接收该 session 的 content-script 消息。content-script native messaging 需要 manifest 中声明 `geckoViewAddons`、`nativeMessagingFromContent` 和 `nativeMessaging`。157.0.20260924084938 的 [`WebExtension.Flags` Javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebExtension.Flags.html)进一步说明：该 permission 对内建/特权扩展设置 `ALLOW_CONTENT_MESSAGING` 标志；候选使用 `ensureBuiltIn`，不授予普通网页任意原生 API。

[`WebExtension.MessageSender` Javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebExtension.MessageSender.html)提供 `webExtension`、`session`、frame `url`、`environmentType` 和 `isTopLevel()`。文档特别提示用 `url` 与 `isTopLevel` 验证预期页面，并指出只有顶层 frame 可被信任。应同时核验固定 extension ID、content-script environment、`sender.session == expectedSession`、顶层标志、HTTP(S) scheme/host，并由宿主再核对该 session 仍是当前选中 tab。建议 `content_scripts.all_frames=false`：只采当前 tab 主 frame，不采同源/跨源子 frame；MDN 确认该值仅在匹配 frame 为 tab 顶层 frame 时注入。`about:`, `data:`, `blob:`、浏览器 UI 与未匹配/受限来源不属于支持目标。

## 权限与生命周期

最少需匹配所有 HTTP(S) 网页的 host permission（例如 MV2 manifest 的 `http://*/*` 与 `https://*/*`），否则浏览器不会在任意网站注入内容脚本。MDN [`host_permissions` 说明](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/host_permissions)列出这些权限的副作用：可对获准来源做跨源 XHR/fetch、注入脚本、读取部分 tab 元信息、接收相应的 `webRequest` 事件；只有另有 `cookies` API 权限时才可读 cookie。本扩展不声明 `cookies`、`webRequest`、tabs APIs、请求/响应体访问或网络权限，但 HTTP(S) 全站点 host access 仍然是重大页面访问授权，应仅随清晰的用户 opt-in 安装，并在 opt-out 时卸载。

GeckoView 的[`WebExtensionController` Javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebExtensionController.html)说明内建扩展会跨 GeckoRuntime 重启持久化；`uninstall()` 返回 `GeckoResult`，完成后才算卸载。因此不能只在 Native 侧停掉 callback。关闭 panel 时必须停止采集/清空 buffer，关闭 session、等待卸载完成并结束旧 Runtime/进程；重新启动时也需清理遗留扩展，再允许恢复网页。授权开关本身不触发安装；安装或卸载失败时不得继续进行页面捕获。

`extensionsWebAPIEnabled(false)`仅控制 Add-on Manager 的 `mozAddonManager` web API（见[`GeckoRuntimeSettings.Builder` Javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.Builder.html)），不应为此功能打开；WebExtension 内建消息是另一套 GeckoView consumer API。Android Manifest permissions 与 `BrowserPrivilegeGuard` allowlist 不需更改。

## 官方资料

该审计中的关键语义可在上面各段链接的 Mozilla GeckoView Javadoc、GeckoView consumer guide、MDN manifest/API 说明与 Mozilla Firefox 128 扩展公告交叉核查。具体 runtime 发包、授权、关闭清理仍须由候选实现、离线测试和可用设备验证共同确认；没有设备时不报告真机 capture 已验证。
