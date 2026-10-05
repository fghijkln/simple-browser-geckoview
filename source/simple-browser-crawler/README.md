# 简浏览 Android 源码工程（v0.5 / 2.8.1）

本目录是公开发行 **v0.5**（应用版本 **2.8.1 / versionCode 18**）的完整 Android Gradle 工程。仓库根目录的[双语项目说明](../../README.md)提供下载、功能摘要与验证范围；[双语发行说明](../../RELEASE-NOTES-v0.5.md)记录此次壁纸与网页调试变化。

新标签页包含七张离线内置壁纸，按设备当前时区的本地日历日期强制每日轮换，没有手动切换入口。开发者设置中的 USB/ADB Firefox DevTools 和页内 Console/Errors 面板均默认关闭。Console 扩展只会在用户明确打开面板时安装，且需接受所有 HTTP/HTTPS 网站的 host permission；面板只在内存保留受限 metadata，不采集日志正文或页面 URL。关闭会清空、卸载并重启 Runtime。细节与限制见[中文网页调试说明](docs/WEB-DEBUGGING.md)、[English guide](docs/WEB-DEBUGGING.en.md)及[网页调试 API 审计](evidence/GECKOVIEW-WEB-DEBUG-API-AUDIT.md)。

Android Manifest 与应用特权守卫沿用 v0.4 的已审阅实现；网页调试和每日轮换没有新增 Android 权限。Release APK 使用项目保留的 Android Debug 签名证书，不是应用商店生产密钥。离线构建或静态检查不等于真机验证；本版没有设备实测网页调试连接或壁纸呈现。完整测试和校验记录见仓库根目录的[构建报告](../../BUILD-REPORT.md)。

## 本地构建

需要 JDK 21、Gradle Wrapper 9.7.1、Android Gradle Plugin 9.4.0 和 Android SDK API 37（minor 2）；`minSdk 29`、`targetSdk 35`，输出仅包括 `arm64-v8a` 和 `x86_64` 两个 ABI。

```bash
bash tools/run-geckoview-local-tests.sh
./gradlew --no-daemon :app:lintRelease :app:assembleRelease
```

项目包含搜索目录、受控抓取、Profile、壁纸轮换、权限策略和网页调试的离线回归测试。关于应用自身特权检查的适用范围，见[特权守卫审计](docs/PRIVILEGE-GUARD-AUDIT.md)。
