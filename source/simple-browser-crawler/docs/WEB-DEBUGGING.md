# 网页调试使用说明（简体中文）

## USB/ADB 远程调试

远程调试默认关闭。在应用的**设置 → 开发者调试**中手动打开“Firefox DevTools USB/ADB 远程调试”后，GeckoView 会在下次 Runtime 创建时开放调试端点；应用因此重启浏览引擎并尝试恢复已打开标签网址，但各标签的前进/后退历史和页面临时状态会重置。

连接步骤：

1. 在 Android 系统设置中启用“开发者选项”和“USB 调试”，用 USB 连接开发电脑，并只授权你信任的电脑。
2. 在桌面 Firefox 打开 `about:debugging`，进入 **Setup** 并选择 **Enable USB Devices**。
3. 在 **USB Devices** 中连接设备，找到简浏览/GeckoView 页面目标并选择 **Inspect**。设备与目标名称会因 Firefox 版本而异。
4. 调试结束后回到应用关闭该开关。应用会再次重启 Runtime，旧调试端点随旧 Runtime 退出。

远程调试连接方可检查并操纵当前网页内容，包括读取 DOM、检查页面状态或执行调试操作。只使用可信开发电脑，并在结束后关闭。它与下文的隐私优先页内面板是**不同能力**；打开远程 DevTools 会授予连接方更宽的检查能力。此功能只配置 GeckoView 的 USB/ADB `remoteDebuggingEnabled`，不启用 Wi‑Fi/局域网监听，不新增 Android 权限、`android:debuggable` 或常驻服务。参考 Mozilla 的 [about:debugging USB 指南](https://firefox-source-docs.mozilla.org/devtools-user/about_colon_debugging/index.html)和 [GeckoRuntimeSettings API](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.Builder.html)。

## 隐私优先：浏览器内 Console / Errors 面板

两步显式同意：在**设置 → 开发者调试**打开“允许使用页内 Console 面板”只记录你的授权，不安装或注入扩展；只有随后点击**打开当前标签 Console 面板（安装并重启）**才会安装 APK 内建的 GeckoView WebExtension。安装前会显示本说明与开关旁权限披露。扩展获准访问所有 `http://*/*` 和 `https://*/*` 页面（全站点 host permission），权限范围比实际采集行为更宽。关闭面板会停采集、清空缓冲区、关闭当前 GeckoSessions、卸载扩展并重启 Runtime；会尝试恢复每个标签的网址，但浏览历史和页面临时状态会重置。若开关未开启或没有待处理的明确打开请求，启动时不会安装扩展，并先清理遗留扩展后才允许恢复网页。

面板只针对当前选中 GeckoSession 的 HTTP(S) **顶层 frame**。`console.log`、`console.warn`、`console.error` 仅记录固定类别、级别与参数个数（最大显示为 64）；未捕获的 JavaScript error 与 unhandled rejection 只记各自的固定错误类别和级别。扩展不会读取或转发 console 参数值、error message、rejection reason、stack、文件名/路径、行号、页面 URL、DOM、表单、输入、cookie 或请求/响应内容。原始字符串、对象属性和值不进入页面消息、native messaging payload、Java 日志对象、缓冲区或 UI。

列表仅显示应用本地生成的相对顺序号、固定类别、级别及参数数量；**不记录绝对时间，也不携带页面 URL 或来源标识**。本地缓冲区只在内存中保留最近 500 个 metadata 事件，每秒最多接收 60 个；可随时点击**清空**。清空会重置序号和速率状态；关面板、标签切换或应用暂停时会停止采集并清除缓冲。除这些允许的 metadata 外，不写历史、偏好、文件、Android logcat 或网络。

实现采用 GeckoView 支持的 MAIN-world 与 ISOLATED-world WebExtension content scripts，而不是 DOM `<script>`、`evaluateJS` 或 `eval` 注入；参见 Mozilla 的 [GeckoView WebExtensions 指南](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/web-extensions.html)与 [content-script 文档](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/content_scripts)。页面可以伪造类别、级别和参数个数，因此这不是可信审计记录；不会显示其伪造的自由文本。Native 端只从 `MessageSender` 元数据本地验证固定扩展 ID、content-script 类型、顶层 frame、GeckoSession 和当前 HTTP(S) origin；origin/URL 仅用于布尔校验，不复制到消息、日志或 UI。

## 验证边界

离线测试使用敏感哨兵字符串（邮箱、token、password、error/stack/path/URL、DOM/cookie/request/response 文本），断言它们不进入 MAIN→ISOLATED 或 ISOLATED→native payload；另测默认关闭、面板请求门控、限速、500 事件上限、清空和 UI/native 字段 allowlist。此类脚本测试并不验证真实 Gecko Runtime 的注入或 DevTools 连接。当前没有 Android 真机或模拟器，因此未声称设备上的 Console 捕获或 USB/ADB 连接已通过运行时验证。
