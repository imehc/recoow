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

Debug 包名为 `imehc.recoow.debug`，Release 为 `imehc.recoow`。Release 启用 R8 和资源压缩。分发环境通过 `RECOOW_KEYSTORE`、`RECOOW_KEYSTORE_PASSWORD`、`RECOOW_KEY_ALIAS`、`RECOOW_KEY_PASSWORD` 四个环境变量提供正式签名；全部未设置时输出未签名 APK，部分缺失时构建失败。密钥文件及本机环境文件不提交。

Android Studio 自动生成的 `gradle/gradle-daemon-jvm.properties` 优先于 `JAVA_HOME`，仅保留本机；CI 显式选择 JDK 17/25。

## 模块

| 模块 | 职责 |
| --- | --- |
| `app` | Hilt 装配、Activity、Tab 与深链导航、系统栏与旧版系统语言适配 |
| `core:domain` | 纯 Kotlin 模型、仓库和平台接口、八工具路由契约 |
| `core:data` | 记录仓库、派生投影、同步接收/应用事务、DataStore 与系统语言适配 |
| `core:database` | 21 实体 Room 存储、同步辅助表、索引、迁移及独占访问协调 |
| `core:network` | Retrofit 协议 v2、严格响应解码、HTTPS 与媒体流传输 |
| `core:sync` | Keystore 凭据、账号同步偏好、设备管理、同步队列、断点恢复、冲突与媒体协调、WorkManager |
| `core:notifications` | 通知权限和渠道、AlarmManager 提醒、WorkManager 重排与系统事件恢复 |
| `core:designsystem` | 唯一 Expressive 主题、页面框架和空状态组件 |
| `core:media` | Photo Picker/拍照、图片优化和裁剪、缩略图/翻页预览、前台录音与播放、可复用媒体输入 |
| `core:calendar` | Android ICU 公历/农历日期及纪念日计算 |
| `core:backup` | 本机回滚备份与中断恢复；iOS SQLite 备份只读检查 |
| `core:scene` | Filament GLB 渲染与 Compose 生命周期适配 |
| `core:location` | Android 原生定位、MapLibre 地图与轨迹显示 |
| `feature:home` / `statistics` | 首页与统计页面 |
| `feature:settings` | 显示偏好、同步连接和冲突处理的 MVVM/UDF 界面 |
| `feature:media` | 素材库管理与照片偏好的 MVVM/UDF 界面 |
| `feature:bills` | 账单录入、筛选、结算与储值账户管理的 MVVM/UDF 界面 |
| `feature:reminders` | 六类打卡排程、进度、逐日明细、补签月历与任务生命周期 |
| `feature:diary` | 日记、心情、标签、六类来源快照、位置与多媒体附件 |
| `feature:food` | 按天饮食、餐别、推荐、账单关联、复制和日级管理 |
| `feature:items` | 物品位置、分类、标签关键词检索与图片 |
| `benchmark` | 核心页面 Baseline Profile 采集与冷启动 Macrobenchmark |

Feature 不互相依赖，不直接访问存储。Domain 不依赖 Android、Hilt、Room 或 Retrofit。

外观偏好由 DataStore 持有。Android 13+ 语言以系统 LocaleManager 为准；API 26–32 使用 DataStore 与 AppCompat。

工具深链格式为 `recoow://tool/{id}`，标识以 `ToolRoute` 为准。通知记录入口为 `recoow://tool/reminders/{recordId}`。外部链接通过单次事件分发，返回栈以首页为根。

账单、日记、饮食和物品也接受 `recoow://tool/{工具标识}/{recordId}` 详情链接。页面间通过应用导航层打开关联记录，Feature 之间不直接依赖。

3D 使用 [Filament](https://github.com/google/filament)（Apache-2.0），地图使用 [MapLibre Native](https://github.com/maplibre/maplibre-native)（BSD-2-Clause）。地图样式及其服务授权由调用方提供；存储坐标使用 WGS84，仅按地图源要求转换显示副本。定位调用方负责权限交互与前台服务生命周期。

## 图片与附件

图片加载使用 [Coil 3.6.3](https://github.com/coil-kt/coil)（Apache-2.0），方向校正使用 AndroidX ExifInterface 1.4.2，播放使用 [AndroidX Media3 1.11.1](https://github.com/androidx/media)（Apache-2.0）。图片在后台限尺寸解码，输出最长边不超过 2048 的 JPEG（质量 78）；缩略图请求上限为 512。裁剪预览与输出共用变换矩阵，支持平移、缩放和连续旋转。

`PhotoInput` 提供单图输入；`AttachmentInput` 编辑照片/录音混合草稿，`ImagePreview` 提供缩放翻页。所属功能的 ViewModel 持有草稿，保存时调用 `MediaRepository.saveAttachments`，将所属记录的 `RecordChange` 与附件一起提交。附件支持 `diary` / `foodEntry`；照片换位保留音频所在的槽位。草稿只保存私有文件路径和元数据，使用 `PreparedMediaSaver` 恢复编辑状态，避免把媒体字节放入 Bundle。

素材新增按内容去重；替换保留素材 ID，局部裁剪生成独立图片，被引用素材不可删除。旧 BLOB 与待下载的 `_media` 引用均由仓库解析；标题和可用状态随数据更新。对象文件使用 SHA-256 内容寻址，读取前验证；清理保留同步历史引用和回滚副本，只删除超过一天的未引用对象。临时草稿及解析缓存保留七天。

照片选取使用系统 Photo Picker，无需申请图库读取权限；拍照委托系统相机，通过限定目录的 FileProvider 返回图片。默认关闭自动加入素材库，默认开启拍照保存到系统图库；API 26–28 写入图库需要权限，拒绝不影响私有图片保存。录音使用 AAC/M4A（mono 44.1 kHz），仅在前台进行，离开页面、进入后台或丢失音频焦点时释放录音器；播放遵循音频焦点和耳机拔出事件。

## 数据与迁移

账单金额使用十进制定点计算，保存为整数分；普通金额四舍五入，折扣乘法向下截断。储值余额由调整流水与账单累计影响投影得到：充值按面值入账，消费按实付扣减；团购到期仅改变展示和统计，不主动改写账单及历史流水。账单和账户写入在同一事务内校验版本，拒绝旧表单覆盖新数据。

Room 2.8.5 管理数据库，kotlinx.serialization 1.10.0 保留 JSON 值的类型、缺失字段及未知字段。`build-tools/generate_database.py` 直接复用仓库的 iOS 契约生成函数，生成字段、实体和注册表；构建不依赖本机已有的 `schema/` 目录。生成的 Kotlin 文件位于 `build/`，不提交。

数据库版本以 `core/database/schema-version.txt` 为准。改变物理字段、约束或索引时递增版本，在 `DatabaseMigrations` 中添加迁移，并保留 `core/database/migrations/recoow-database-v{版本}.sql` 历史；不得修改已发布的结构或使用破坏性迁移。Room 自动导出的 JSON 位于 `core/database/build/roomSchemas/`，不提交。

构建后可用 `python3 build-tools/generate_database.py --room-schema <导出的JSON路径> --output core/database/migrations` 生成新版本的 SQL。`./gradlew :core:database:checkSchemaHistory` 检查实际结构与 SQL 历史一致，CI 同样执行此检查。

业务写入通过 `RecordRepository` 完成；同步回放使用独立入口，避免再次生成本地变更。媒体及偏好写入须使用同一个 `DatabaseHost` 访问边界。维护操作使用 `guarded`：先备份数据库、媒体和偏好，再开始写入；异常或进程中断后先恢复再开放访问。

数据和回滚目录位于应用 `noBackupFilesDir`。本机回滚包绑定安装身份，包含同步状态、不含账号凭据；它与 iOS `.recoowbackup` 互通格式是不同的格式。

## 同步

Retrofit 3 与 OkHttp 5 对接仓库后端的协议 v2。正式连接要求 HTTPS；Debug 仅额外允许 `localhost`、`127.0.0.1` 和模拟器宿主地址 `10.0.2.2` 的 HTTP。禁止带凭据、查询串或片段的服务器地址，认证请求不跟随重定向。

设备令牌和待确认的恢复密钥以 Android Keystore AES-GCM 加密，放在回滚目录之外。业务记录使用 `DeviceIdentity` 的本地 UUID，服务端签发的设备 ID 仅用于连接身份。

同步默认关闭，按规范化服务器地址与账号分别保存；关闭会取消正在执行的同步，并停止手动及自动同步。开启后，WorkManager 在联网约束下执行合并后的同步请求，并持久化重试；前台与本地修改触发即时任务，周期任务补偿进程退出期间的变化。媒体在保存修改时按内容寻址固定，上传确认后才提交引用；收到的数据先进入持久化 inbox，再按依赖应用。通知任务独立保留，由功能模块的幂等调度器消费。

协议测试可运行 `./gradlew :core:network:testDebugUnitTest`。真实后端测试使用隔离的测试账号，通过设备测试参数 `syncBaseUrl` 指定地址，例如 `-Pandroid.testInstrumentationRunnerArguments.syncBaseUrl=http://10.0.2.2:8080/`；未提供地址时跳过网络联调。

## 打卡与通知

六种排程使用本地日历计算，历史日期键保持固定。打卡和补签写入独立的 `reminder_completions`；撤销只为对应明细写墓碑，父记录的完成日期列表由投影维护。连续挑战遇到漏签需先补签；坚持目标支持暂停，累计实际暂停时长，不改写历史日期。

通知使用独立渠道，每个任务安排下一次本体和提前提醒。Android 13+ 申请通知权限；Android 12+ 缺少精确闹钟权限时使用非精确调度。WorkManager、应用前台、设备重启、时间及权限变化触发恢复。拒绝通知不阻止数据保存和打卡。系统强行停止后需重新打开应用才能恢复后台任务。

## 日记、饮食与物品

日记连同来源快照及附件在同一事务内保存，删除标签或来源保留日记中的历史摘要。日记当前位置仅在用户操作后获取，离开编辑页或进入后台即取消定位。物品分类删除不连删物品，也不清空历史分类引用；编辑文字保留内嵌图片及素材引用。

饮食以本地自然日分组，日标题 ID 沿用 `food-day:<day_start_at>`。复制单条或整餐只改变日期、保留时分秒，清空账单关联及照片/录音。删除整日会重新校验确认时的记录集合，事务内删除当日条目、附件及日标题，保留关联账单。三个模块均校验编辑版本，附件或关联发生变化时拒绝旧草稿覆盖。

## 性能采集

`baseline` 和 `benchmark` 都使用独立包名 `imehc.recoow.benchmark` 与测试签名，不参与正式分发。`baseline` 保留类名以采集 Profile，`benchmark` 使用与 Release 相同的 R8 和资源压缩设置。

```sh
./gradlew :benchmark:connectedBaselineAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=imehc.recoow.benchmark.CoreBaselineProfile
./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=imehc.recoow.benchmark.CoreStartupBenchmark
```

采集结果位于 `benchmark/build/outputs/connected_android_test_additional_output/`。将审核后的 `baseline-prof.txt` 与 `startup-prof.txt` 放入 `app/src/main/`，AGP 会重写为 Release 所需的 Profile。测量性能收益及设置回归阈值须使用真实设备；模拟器用于验证采集和打包链路。
