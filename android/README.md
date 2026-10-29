# recoow Android

使用 Kotlin、Jetpack Compose Material 3 Expressive 与 MVVM/UDF/Clean 架构的 Android 客户端。

## 构建

- Kotlin 2.3.20，AGP 9.2.0（内置 Kotlin），KSP 2.3.12，Hilt 2.60.1。
- Gradle Wrapper 9.4.1，发行包带 SHA-256 校验。JDK 17 是最低版本，支持使用更高的兼容版本；Java/Kotlin 字节码目标统一为 17。
- `compileSdk 37`、`targetSdk 36`、`minSdk 26`、Build Tools 37.0.0。
- Compose BOM 2026.09.00、Material 3 1.5.0-beta02。该版本公开 Expressive 主题入口并要求 compileSdk 37；采用默认颜色，不启用动态配色或另设品牌主题。

安装 Android SDK 和 Python 3 后，在本机 `local.properties` 设置 `sdk.dir`，或设置 `ANDROID_HOME`。从本目录运行：

```sh
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :core:domain:test :core:data:testDebugUnitTest :feature:settings:testDebugUnitTest lint
./gradlew :app:connectedDebugAndroidTest
./gradlew :core:database:testDebugUnitTest :core:database:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest
```

Debug 包名为 `imehc.recoow.debug`，Release 为 `imehc.recoow`。Release 启用 R8 和资源压缩，输出未签名 APK；正式签名由分发环境提供，不使用 Debug 密钥发布。

Android Studio 自动生成的 `gradle/gradle-daemon-jvm.properties` 优先于 `JAVA_HOME`，仅保留本机；CI 显式选择 JDK 17/25。

## 模块

| 模块 | 职责 |
| --- | --- |
| `app` | Hilt 装配、Activity、Tab 与深链导航、系统栏与旧版系统语言适配 |
| `core:domain` | 纯 Kotlin 模型、仓库和平台接口、八工具路由契约 |
| `core:data` | 记录仓库、派生投影、同步接收/应用事务、DataStore 与系统语言适配 |
| `core:database` | 21 实体 Room 存储、同步辅助表、索引、迁移及独占访问协调 |
| `core:network` | Retrofit 协议 v2、严格响应解码、HTTPS 与媒体流传输 |
| `core:sync` | Keystore 凭据、同步队列、断点恢复、冲突与媒体协调、WorkManager |
| `core:designsystem` | 唯一 Expressive 主题、页面框架和空状态组件 |
| `core:calendar` | Android ICU 公历/农历日期及纪念日计算 |
| `core:backup` | 本机回滚备份与中断恢复；iOS SQLite 备份只读检查 |
| `core:scene` | Filament GLB 渲染与 Compose 生命周期适配 |
| `core:location` | Android 原生定位、MapLibre 地图与轨迹显示 |
| `feature:home` / `statistics` | 首页与统计页面 |
| `feature:settings` | 显示偏好、同步连接和冲突处理的 MVVM/UDF 界面 |

Feature 不互相依赖，不直接访问存储。Domain 不依赖 Android、Hilt、Room 或 Retrofit。

外观偏好由 DataStore 持有。Android 13+ 语言以系统 LocaleManager 为准；API 26–32 使用 DataStore 与 AppCompat。

工具深链格式为 `recoow://tool/{id}`，标识以 `ToolRoute` 为准。外部链接通过单次事件分发，返回栈以首页为根。

3D 使用 [Filament](https://github.com/google/filament)（Apache-2.0），地图使用 [MapLibre Native](https://github.com/maplibre/maplibre-native)（BSD-2-Clause）。地图样式及其服务授权由调用方提供；存储坐标使用 WGS84，仅按地图源要求转换显示副本。定位调用方负责权限交互与前台服务生命周期。

## 数据与迁移

Room 2.8.5 管理数据库，kotlinx.serialization 1.10.0 保留 JSON 值的类型、缺失字段及未知字段。`build-tools/generate_database.py` 直接复用仓库的 iOS 契约生成函数，生成字段、实体和注册表；构建不依赖本机已有的 `schema/` 目录。生成的 Kotlin 文件位于 `build/`，不提交。

数据库版本以 `core/database/schema-version.txt` 为准。改变物理字段、约束或索引时递增版本，在 `DatabaseMigrations` 中添加迁移，并保留 `core/database/migrations/recoow-database-v{版本}.sql` 历史；不得修改已发布的结构或使用破坏性迁移。Room 自动导出的 JSON 位于 `core/database/build/roomSchemas/`，不提交。

构建后可用 `python3 build-tools/generate_database.py --room-schema <导出的JSON路径> --output core/database/migrations` 生成新版本的 SQL。`./gradlew :core:database:checkSchemaHistory` 检查实际结构与 SQL 历史一致，CI 同样执行此检查。

业务写入通过 `RecordRepository` 完成；同步回放使用独立入口，避免再次生成本地变更。媒体及偏好写入须使用同一个 `DatabaseHost` 访问边界。维护操作使用 `guarded`：先备份数据库、媒体和偏好，再开始写入；异常或进程中断后先恢复再开放访问。

数据和回滚目录位于应用 `noBackupFilesDir`。本机回滚包绑定安装身份，包含同步状态、不含账号凭据；它与 iOS `.recoowbackup` 互通格式是不同的格式。

## 同步

Retrofit 3 与 OkHttp 5 对接仓库后端的协议 v2。正式连接要求 HTTPS；Debug 仅额外允许 `localhost`、`127.0.0.1` 和模拟器宿主地址 `10.0.2.2` 的 HTTP。禁止带凭据、查询串或片段的服务器地址，认证请求不跟随重定向。

设备令牌和待确认的恢复密钥以 Android Keystore AES-GCM 加密，放在回滚目录之外。业务记录使用 `DeviceIdentity` 的本地 UUID，服务端签发的设备 ID 仅用于连接身份。

WorkManager 在联网约束下执行合并后的同步请求，并持久化重试；前台与本地修改触发即时任务，周期任务补偿进程退出期间的变化。媒体在保存修改时按内容寻址固定，上传确认后才提交引用；收到的数据先进入持久化 inbox，再按依赖应用。通知任务独立保留，由功能模块的幂等调度器通过 `NotificationDelivery` 消费。

协议测试可运行 `./gradlew :core:network:testDebugUnitTest`。真实后端测试使用隔离的测试账号，通过设备测试参数 `syncBaseUrl` 指定地址，例如 `-Pandroid.testInstrumentationRunnerArguments.syncBaseUrl=http://10.0.2.2:8080/`；未提供地址时跳过网络联调。
