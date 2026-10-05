# 简浏览 Android 源码工程（本地 v0.6 候选 / 2.9.0）

本目录是基于公开 v0.5 commit `26a28a72d10a7907e8a995c8f2cf03568765af5d` 的 Android Gradle 工程修改副本。**v0.6/2.9.0 仅为本地候选，尚未远端发布；APK 由 Android Debug 证书签名，不是商店生产签名。**项目范围、兼容说明、剩余技术边界、回归/build/Manifest审计及真机验证限制见仓库顶层的[中文报告](../../REPORT.md)、[简中发行说明](../../RELEASE-NOTES-v0.6-zh-CN.md)、[英文发行说明](../../RELEASE-NOTES-v0.6-en.md)和[Manifest审计](../../MANIFEST-AUDIT-v0.6.txt)。

## v0.6候选变化

启动时显示简中/英文风险告知，列出应用特权检查发现并由用户选择继续或退出；非致命守卫状态不再单独阻断，致命损坏仍失败。USB/ADB DevTools与页内Console默认开放且可在设置关闭；Console安装仍请求全站host permission，输出当前顶层页可含敏感信息的原始日志文本/错误/stack。Console条数、单条文字/参数长度和事件速率均提供有限值或“不限”，不限仍受Android/Java堆与数据类型边界约束。

摄像头、麦克风、位置网页请求可启动系统runtime permission弹窗，系统授予后仍逐站点确认。抓取由用户显式启动、前台串行执行，提供页数、队列、总时长、单页/robots字节、同源重定向、请求间隔和429/503等待的有限值或0=不限档。实际无限内存并无保证；429/503逐次确认且遵守服务器`Retry-After`，认证/CAPTCHA/付费墙不会被绕过，抓取器不共享浏览器登录态。

没有新增 Android Manifest 权限，未修改Cookie/profile隔离、DNS/ETP/WebRTC默认或用户私有存储路径。风险说明提醒风险，不改变法定责任或授权状态。**未进行Android真机安装或运行验证。**

## 本地构建与离线回归

需要 JDK 21、Gradle Wrapper 9.7.1、Android Gradle Plugin 9.4.0 和 Android SDK API 37；`minSdk 29`、`targetSdk 35`；输出 `arm64-v8a` 与 `x86_64` 两种 ABI。

```bash
bash tools/run-crawler-local-mock.sh
bash tools/run-geckoview-local-tests.sh
./gradlew --offline --no-daemon :app:lintRelease :app:assembleRelease
```

离线回归不是Android设备或真实网站测试。请先阅读仓库顶层报告中明示的“0=取消应用配置上限”与系统/内存/网络剩余边界。
