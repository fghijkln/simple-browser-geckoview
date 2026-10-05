# 手动权限与设备能力核对

应用版本 2.5.0 / versionCode 14 只为已实现的网站摄像头、麦克风与定位功能声明可选 Android 权限。应用不调用 Android 危险权限运行时申请 API；GeckoView 回调只检查当前授权状态。缺少权限时，相关敏感网页请求会被拒绝并提示用户自行到 Android 设置管理应用权限，普通浏览继续。

| 能力 | 分类 | 实际行为 |
|---|---|---|
| 网站摄像头 | 设置里手动授予 | 声明 `android.permission.CAMERA`；camera、camera.any 与 autofocus 硬件特性均为 `required=false`，防止 Android/Google Play 将无摄像头设备过滤（见 Android [`<uses-feature>` 文档](https://developer.android.com/guide/topics/manifest/uses-feature-element)）。手动授予后网站仍要逐站确认；没有摄像头硬件时拒绝请求。 |
| 网站麦克风 | 设置里手动授予 | 声明 `android.permission.RECORD_AUDIO`，且 `android.hardware.microphone` 为 `required=false`。无麦克风硬件时拒绝请求；WebRTC PeerConnection 防护默认开启，开启时媒体功能可能仍不可用。 |
| 网站定位 | 设置里手动授予 | 声明 `ACCESS_COARSE_LOCATION` 与 `ACCESS_FINE_LOCATION`。系统权限不足时拒绝；授予后仍需要网站确认。粗略/精确选择会影响实际精度。 |
| 选照片、上传文件、导入扩展 ZIP | 无需媒体库权限 | 使用 `ACTION_OPEN_DOCUMENT`，读取用户明确选择的 URI。未声明 `READ_MEDIA_*` 或广泛存储读取权限。参见 Android [Photo Picker 文档](https://developer.android.com/training/data-storage/shared/photopicker)。 |
| 剪贴板 | 没有可在 App permissions 单独授予的常规运行时权限 | 项目 Java 代码没有直接调用 `ClipboardManager`。浏览器编辑/选择操作由 Android 与 GeckoView 系统集成处理；[ClipboardManager API](https://developer.android.com/reference/android/content/ClipboardManager)指出，应用未处于输入焦点且不是默认输入法时读取可能返回 `null`。这属于平台限制，不是待授予的 App permission。 |
| 普通加速度计/陀螺仪 | 不需要身体传感器权限；本应用没有原生传感器实现 | Android [运动传感器文档](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)区分加速度计、陀螺仪与步数检测器；步数检测在 Android 10+ 才涉及 `ACTIVITY_RECOGNITION`。`BODY_SENSORS` 用于心率等身体内部数据（见[权限 API](https://developer.android.com/reference/android/Manifest.permission)）。项目 Android 层没有 `SensorManager` 调用；GeckoView 网页 Device Motion/Generic Sensor 在设备上的行为未验证，不作功能保证。 |
| 步数、活动识别、心率等健康数据 | 当前不支持/不可授权 | 不实现这些功能；未声明 `ACTIVITY_RECOGNITION`、`BODY_SENSORS` 或 `BODY_SENSORS_BACKGROUND`。 |
| GeckoView 未列入支持清单的系统权限与屏幕共享源 | 当前不支持/不可授权 | 未声明权限（例如 `ACCESS_LOCAL_NETWORK`）及非摄像头/麦克风媒体源会被拒绝；提示为当前版本不支持，不误导用户到设置中授权。 |
| DNS-only VPN | 单独的用户主动系统确认 | 既有可选 VPN 原型仅在用户主动点击设置项时调用 Android VPN 授权流程；它不在启动或网页权限回调中触发，也不是 Manifest 中的危险权限。 |

Android 危险权限与网站权限是两道不同的确认。系统权限必须由用户手动授予，之后网站请求才会显示逐站确认；GeckoView 会把网站决定保存到当前浏览环境，见 [GeckoView site permissions 文档](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/permissions.html)。本版本没有独立逐站权限管理界面；Android 系统设置仍可撤销摄像头、麦克风或位置的应用级授权。系统设置名称随 Android/OEM 变化。
