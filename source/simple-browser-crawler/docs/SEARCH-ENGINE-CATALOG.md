# 内置搜索引擎目录

当前内置目录位于 `app/src/main/assets/search-engines/catalog.v1.json`。该文件使用 UTF-8 JSON，根对象包含 `schemaVersion`、`catalogVersion` 和 `engines`。应用启动时由 `SearchEngineCatalog` 校验版本、ID 唯一性、默认 `google` 项和可执行 HTTPS 模板，再安装到运行时模型；没有已核验模板的目录项目可以作为描述/筛选条目保留，但不会执行查询。

## 记录字段

- `id`：稳定的小写 ASCII ID。不要因为显示名称或模板更新而更改已发布 ID；旧版本保存在 SharedPreferences 中的 ID 依赖此值。
- `name`、`monogram`：显示名称和无图标时可用的短标记。
- `urlTemplate`：运行时 HTTPS URL，仅包含一个 `{query}`，可位于查询参数或路径片段；不能包含 URL 片段或用户信息。路径片段以 UTF-8 URI 路径安全编码。空字符串表示此条目不可执行。
- `category`：既有访问分类枚举 `FREE`、`ACCOUNT_REQUIRED`、`PAID`。不要用功能类型取代这些类别。
- `purpose`、`accessNote`、`caveat`：用途与访问说明；`caveat` 可为空。
- `featureCategory`：为旧目录/自定义引擎保留的单个功能 facet。
- `functionalSubcategories`：PDF 来源的多值功能细类；选择器逐标签筛选，单项可以同时出现在多个细类中。
- `pdfPrimaryCategory`、`pdfSecondaryCategory`：PDF 一级、二级分类。
- `templateStatus`、`search_template`、`templateEvidenceUrl`、`templateEvidenceNote`、`templateAccessNote`：分段核验记录。仅 `verified` 状态可承载该来源 `search_template`；其他状态必须为空。旧目录 URL 另由 `urlTemplateOrigin=legacy_compatibility` 标记，以维护已发布 ID 的既有行为，不会改写核验状态。
- `pdfFeeClassification`、`pdfOperationalStatus`、`accessAssessment`：保留费用/运营原文与访问类别映射说明。边界不清时在 `accessNote`/`caveat` 提示，不将 API 凭据/计费服务当作免费匿名网页搜索。

## 性能与兼容性

`SearchEngine` 在载入时预先组合并小写化搜索字段；`SearchEngineCatalog.filter` 只比较现成字符串，多值功能标签按任一命中匹配。选择器采用 Android `ListView` / `BaseAdapter` 回收行，通过 `convertView` 复用视图；输入筛选有短防抖，不会逐字符创建全量行。内置图标由单线程后台任务读取、按显示尺寸采样和解码，使用有界内存缓存，并先显示系统搜索图标作为缺图回退。

原有三种访问分类、21 个既有 ID、既有运行时 URL 模板、`search-engine` 偏好键、自定义引擎的 SharedPreferences 编码格式及 Google 默认回退保持不变。当前目录另含 PDF 500 项与 2 个旧目录非重叠项。`tools/search_engine_catalog_smoke.py` 可在无 Java/Android SDK 环境下检查数据来源一致性和关键实现结构；这不替代 Java/Android 编译或实时可用性测试。
