# 旧版本地内容跟踪规则（已退役）

从简浏览 2.2.0 起，旧版六域名本地过滤表、按 Public Suffix List 计算的站点例外逻辑及其资源文件不再随 APK 提供，也不参与请求拦截。当前应用使用 GeckoView 原生 ETP Strict、AntiTracking Strict、严格社交跟踪保护和查询参数剥离；应用没有经过验证的逐站例外控制。详细行为和边界见 [`NETWORK-PRIVACY-AUDIT.md`](NETWORK-PRIVACY-AUDIT.md)。

早期版本曾依据供应商公开资料手工整理少量分析/遥测域名，并曾采用完整 Mozilla Public Suffix List 区分注册域。那些旧规则并非完整或自动更新的跟踪数据库，也不应视为当前 APK 的拦截内容。本文件保留此退役状态说明，防止旧版来源记录被误读为 2.2.0 当前功能。