# recoow Android

使用 Kotlin、Jetpack Compose Material 3 Expressive 与 MVVM/UDF/Clean 架构的 Android 客户端。

## 构建

- Kotlin 2.3.20，AGP 9.2.0（内置 Kotlin），KSP 2.3.12，Hilt 2.60.1。
- Gradle Wrapper 9.4.1，发行包带 SHA-256 校验。JDK 17 是最低版本，支持使用更高的兼容版本；Java/Kotlin 字节码目标统一为 17。
- `compileSdk 37`、`targetSdk 36`、`minSdk 26`、Build Tools 37.0.0。
- Compose BOM 2026.09.00、Material 3 1.5.0-beta02。该版本公开 Expressive 主题入口并要求 compileSdk 37；采用默认颜色，不启用动态配色或另设品牌主题。

安装 Android SDK 后，在本机 `local.properties` 设置 `sdk.dir`，或设置 `ANDROID_HOME`。从本目录运行：

```sh
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :core:domain:test :core:data:testDebugUnitTest :feature:settings:testDebugUnitTest lint
./gradlew :app:connectedDebugAndroidTest
```

Debug 包名为 `imehc.recoow.debug`，Release 为 `imehc.recoow`。Release 启用 R8 和资源压缩，输出未签名 APK；正式签名由分发环境提供，不使用 Debug 密钥发布。

Android Studio 自动生成的 `gradle/gradle-daemon-jvm.properties` 优先于 `JAVA_HOME`，仅保留本机；CI 显式选择 JDK 17/25。

## 模块

| 模块 | 职责 |
| --- | --- |
| `app` | Hilt 装配、Activity、Tab 与深链导航、系统栏与旧版系统语言适配 |
| `core:domain` | 纯 Kotlin 模型、仓库和平台接口、八工具路由契约 |
| `core:data` | DataStore 持久化；Android 13+ 系统应用语言观察与设置 |
| `core:designsystem` | 唯一 Expressive 主题、页面框架和空状态组件 |
| `core:calendar` | Android ICU 公历/农历日期及纪念日计算 |
| `core:backup` | iOS SQLite 备份的只读格式检查、元数据及内容概览 |
| `core:scene` | Filament GLB 渲染与 Compose 生命周期适配 |
| `core:location` | Android 原生定位、MapLibre 地图与轨迹显示 |
| `feature:home` / `statistics` | 首页与统计页面 |
| `feature:settings` | MVVM/UDF 显示设置、单份 StateFlow、失败与重试、可恢复选择弹窗 |

Feature 不互相依赖，不直接访问存储。Domain 不依赖 Android、Hilt、Room 或 Retrofit。

外观偏好由 DataStore 持有。Android 13+ 语言以系统 LocaleManager 为准；API 26–32 使用 DataStore 与 AppCompat。

工具深链格式为 `recoow://tool/{id}`，标识以 `ToolRoute` 为准。外部链接通过单次事件分发，返回栈以首页为根。

3D 使用 [Filament](https://github.com/google/filament)（Apache-2.0），地图使用 [MapLibre Native](https://github.com/maplibre/maplibre-native)（BSD-2-Clause）。地图样式及其服务授权由调用方提供；存储坐标使用 WGS84，仅按地图源要求转换显示副本。定位调用方负责权限交互与前台服务生命周期。
