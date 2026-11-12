# recoow Android

使用 Kotlin、Jetpack Compose Material 3 Expressive 与 MVVM/UDF/Clean 架构的 Android 客户端。

协作遵循 [AGENTS.md](../AGENTS.md)，UI 遵循 [DESGIN.MD](../DESGIN.MD)。布局与间距使用 Android 共享组件和 `RecoowLayout`，不照搬 iOS 的 pt、原生分组边距或手势。

## 构建

- Kotlin 2.3.20，AGP 9.2.0（内置 Kotlin），KSP 2.3.12，Hilt 2.60.1。
- Gradle Wrapper 9.4.1，发行包带 SHA-256 校验。JDK 17 是最低版本，支持使用更高的兼容版本；Java/Kotlin 字节码目标统一为 17。
- `compileSdk 37`、`targetSdk 36`、`minSdk 26`、Build Tools 37.0.0。
- Compose BOM 2026.09.00、Material 3 1.5.0-beta02。该版本公开 Expressive 主题入口并要求 compileSdk 37；`RecoowTheme` 注入共享品牌色板、语义字号与形状，不启用动态配色。

安装 Android SDK 和 Python 3 后，在本机 `local.properties` 设置 `sdk.dir`，或设置 `ANDROID_HOME`。从本目录运行：

```sh
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :core:domain:test :core:data:testDebugUnitTest :feature:settings:testDebugUnitTest lint
./gradlew :app:connectedDebugAndroidTest
./gradlew :core:database:testDebugUnitTest :core:database:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest
```

Debug 包名为 `imehc.recoow.debug`，Release 为 `imehc.recoow`。Release 启用 R8 和资源压缩。分发环境通过 `RECOOW_KEYSTORE`、`RECOOW_KEYSTORE_PASSWORD`、`RECOOW_KEY_ALIAS`、`RECOOW_KEY_PASSWORD` 四个环境变量提供正式签名；全部未设置时输出未签名 APK，部分缺失时构建失败。密钥文件及本机环境文件不提交。

应用级设备测试使用独立测试模拟器或测试设备，避免安装、卸载与测试清理影响保留的记录、媒体、偏好和权限。需要复用界面演示环境时，先保存并验证完整快照，测试后恢复并核对演示状态。

`./gradlew :app:verifyReleaseArtifact` 检查实际 Release APK 的权限、组件、原生库对齐和 Profile；默认不验证正式签名。[APK 分发说明](RELEASE.md)包含证书固定与升级校验流程，[数据与权限说明](PRIVACY.md)描述客户端的数据处理方式。

Android Studio 自动生成的 `gradle/gradle-daemon-jvm.properties` 优先于 `JAVA_HOME`，仅保留本机；CI 统一使用 JDK 17。

## 模块

| 模块 | 职责 |
| --- | --- |
| `app` | Hilt 装配、Activity、Tab 与深链导航、系统栏、小组件和数据维护协调 |
| `core:domain` | 纯 Kotlin 模型、仓库和平台接口、八工具路由契约 |
| `core:data` | 记录仓库、派生投影、同步事务、备份转换、存储管理、DataStore 与系统语言适配 |
| `core:database` | 21 实体 Room 存储、同步辅助表、索引、迁移及独占访问协调 |
| `core:network` | Retrofit 协议 v2、严格响应解码、HTTPS 与媒体流传输 |
| `core:sync` | Keystore 凭据、账号同步偏好、设备管理、同步队列、断点恢复、冲突与媒体协调、WorkManager |
| `core:notifications` | 通知权限和渠道、AlarmManager 提醒、WorkManager 重排与系统事件恢复 |
| `core:designsystem` | 唯一 Expressive 主题、页面框架和空状态组件 |
| `core:media` | Photo Picker/拍照、图片优化和裁剪、缩略图/翻页预览、前台录音与播放、可复用媒体输入 |
| `core:calendar` | Android ICU 公历/农历日期及纪念日计算 |
| `core:backup` | 本机回滚备份与中断恢复；iOS SQLite 备份检查与互通结构 |
| `core:scene` | Filament GLB 渲染与 Compose 生命周期适配 |
| `core:location` | Android 原生定位、MapLibre 地图与轨迹显示 |
| `feature:home` / `statistics` | 首页与统计页面 |
| `feature:settings` | 显示与照片偏好、数据管理、同步连接和冲突处理的 MVVM/UDF 界面 |
| `feature:media` | 素材库管理与照片偏好的 MVVM/UDF 界面 |
| `feature:bills` | 账单录入、筛选、结算与储值账户管理的 MVVM/UDF 界面 |
| `feature:reminders` | 六类打卡排程、进度、逐日明细、补签月历与任务生命周期 |
| `feature:diary` | 日记、心情、标签、六类来源快照、位置与多媒体附件 |
| `feature:food` | 按天饮食、餐别、推荐、账单关联、复制和日级管理 |
| `feature:items` | 物品位置、分类、标签关键词检索与图片 |
| `feature:anniversaries` | 公历/农历纪念日、倒计时、周年与通知设置 |
| `feature:decision` | 集合、加权候选、3D 转盘和持久抽选历史 |
| `feature:tracks` | 轨迹录制、历史、分段编辑、回放、洞察与时间范围关联 |
| `feature:history` | 八类统一时间线、周历、筛选搜索、游标分页与业务删除计划 |
| `benchmark` | 八工具与统计/历史的 Baseline Profile，冷启动、列表帧耗时和 3D 内存 Macrobenchmark |

Feature 不互相依赖，不直接访问存储。Domain 不依赖 Android、Hilt、Room 或 Retrofit。

外观偏好由 DataStore 持有。Android 13+ 语言以系统 LocaleManager 为准；API 26–32 使用 DataStore 与 AppCompat。

工具深链格式为 `recoow://tool/{id}`，标识以 `ToolRoute` 为准。通知记录入口为 `recoow://tool/reminders/{recordId}`。外部链接通过单次事件分发，返回栈以首页为根。

账单、日记、饮食、物品、纪念日、抽选历史和轨迹也接受 `recoow://tool/{工具标识}/{recordId}` 详情链接。页面间通过应用导航层打开关联记录，Feature 之间不直接依赖。

3D 使用 [Filament](https://github.com/google/filament)（Apache-2.0），地图使用 [MapLibre Native](https://github.com/maplibre/maplibre-native)（BSD-2-Clause）的 Vulkan/OpenGL 双后端包：有 Vulkan 能力时优先使用 Vulkan，其余设备回退到 OpenGL ES。地图组件接收样式及坐标系，轨迹页面使用 [OpenFreeMap](https://openfreemap.org/) Liberty 底图并保留来源署名；存储坐标使用 WGS84，仅按地图源要求转换显示副本。

地图无需商业地图 Key。`core:location/MapNetwork` 为地图提供独立的 OkHttp 缓存与域名解析回退：OpenFreeMap 域名的系统解析失败时使用 HTTPS DNS，回退仅作用于地图请求，不修改模拟器 / 手机的 DNS 设置或业务 API 网络客户端。在线底图加载完成以瓦片解析和完整帧渲染为准；纯路线回退仍明确显示底图不可用。

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

设置中的数据管理通过系统文件选择器读写 `.recoowbackup`，包含全部 21 类实体、媒体及可移植偏好。导出使用 iOS schema 36 的独立 SQLite 副本；元数据分别记录互通结构、来源平台和源数据库版本。可直接导入 iOS schema 34–36 的备份；更早的开发期备份需先在当前 iOS 应用中迁移并重新导出。互通结构文件为 `core/backup/src/main/assets/ios-backup-schema-v36.sql`，与 Room 迁移独立维护。

增量导入按八工具选择范围，仅补缺失 ID；覆盖导入替换业务和可移植偏好，保留本机身份与当前连接，重新建立同步及通知任务。优化存储保留墓碑、历史媒体和最新恢复副本。重置清除本地记录、媒体、偏好、恢复备份及服务器绑定，不删除云端账号。

桌面小组件使用原生 App Widget/RemoteViews，每个实例独立选择轨迹、抽选、物品、打卡、账单、日记或纪念日入口；可长按更改配置，未配置时点击进入选择。小组件不做后台轮询，点击通过工具深链打开应用。

## 同步

Retrofit 3 与 OkHttp 5 对接仓库后端的协议 v2。正式连接要求 HTTPS；Debug 仅额外允许 `localhost`、`127.0.0.1` 和模拟器宿主地址 `10.0.2.2` 的 HTTP。禁止带凭据、查询串或片段的服务器地址，认证请求不跟随重定向。地址无效单独提示，不再误报为协议不兼容。

连接已有账号选择配对，并使用主设备生成的一次性配对码；创建账号会建立独立账号。注册令牌由服务器管理员设置（`RECOOW_BOOTSTRAP_TOKEN`），未设置时留空，与配对码和恢复密钥不同。

「设置 → 账号与云同步 → Web 访问」由当前账号主设备管理。开关以服务器返回值为准，保存期间禁止重复操作；旧服务器显示对应说明，非主设备隐藏整个分组。开启后刷新已有 Web 页面即可应用，关闭后服务器立即拒绝该账号的 Web 写入；此授权独立于自动同步开关。

设备令牌和待确认的恢复密钥以 Android Keystore AES-GCM 加密，放在回滚目录之外。业务记录使用 `DeviceIdentity` 的本地 UUID，服务端签发的设备 ID 仅用于连接身份。

同步默认关闭，按规范化服务器地址与账号分别保存；关闭会取消正在执行的同步，并停止手动及自动同步。开启后，WorkManager 在联网约束下执行合并后的同步请求，并持久化重试；前台与本地修改触发即时任务，周期任务补偿进程退出期间的变化。媒体在保存修改时按内容寻址固定，上传确认后才提交引用；收到的数据先进入持久化 inbox，再按依赖应用。通知任务独立保留，由功能模块的幂等调度器消费。

协议测试可运行 `./gradlew :core:network:testDebugUnitTest`。真实后端测试使用隔离的测试账号，通过设备测试参数 `syncBaseUrl` 指定地址，例如 `-Pandroid.testInstrumentationRunnerArguments.syncBaseUrl=http://10.0.2.2:8080/`；未提供地址时跳过网络联调。

## 打卡与通知

六种排程使用本地日历计算，历史日期键保持固定。打卡和补签写入独立的 `reminder_completions`；撤销只为对应明细写墓碑，父记录的完成日期列表由投影维护。连续挑战遇到漏签需先补签；坚持目标支持暂停，累计实际暂停时长，不改写历史日期。

通知使用独立渠道，每个任务安排下一次本体和提前提醒。Android 13+ 申请通知权限；Android 12+ 缺少精确闹钟权限时使用非精确调度。WorkManager、应用前台、设备重启、时间及权限变化触发恢复。拒绝通知不阻止数据保存和打卡。系统强行停止后需重新打开应用才能恢复后台任务。

## 日记、饮食与物品

日记连同来源快照及附件在同一事务内保存，删除标签或来源保留日记中的历史摘要。日记当前位置仅在用户操作后获取，离开编辑页或进入后台即取消定位。物品分类删除不连删物品，也不清空历史分类引用；编辑文字保留内嵌图片及素材引用。

饮食以本地自然日分组，日标题 ID 沿用 `food-day:<day_start_at>`。复制单条或整餐只改变日期、保留时分秒，清空账单关联及照片/录音。删除整日会重新校验确认时的记录集合，事务内删除当日条目、附件及日标题，保留关联账单。三个模块均校验编辑版本，附件或关联发生变化时拒绝旧草稿覆盖。

## 纪念日与抽选

纪念日保留原始时间戳；公历/农历切换改变重复规则与主显示，不转换为另一个原始日期。农历输入按 ICU 校验实际年月日及闰月，发生日同时显示公历和农历。下一次发生日期沿用 iOS 的构造和逐年递增顺序；通知使用独立渠道，复用系统事件与 WorkManager 恢复入口。通知收据与业务字段指纹防止重复投递及旧排程误发，同步确认的存储版本变化不影响有效提醒。

抽选在事务中读取当前启用候选，以 `max(1, weight)` 的相对权重抽样并保存历史；使用大整数累加避免导入权重溢出。表单权重为 1–100，未修改的历史大权重保持原值。结果展示和动画均以已提交历史为准。删除集合软删候选，保留全部历史及文本/图片快照；历史可以逐条删除。

Filament 转盘读取共享 `assets/decision-scene/` 中的 GLB 和模型目录，构建复制到生成资产目录，不读取 USDZ。模型名称按 Unicode NFKC、空白归一化及小写做精确别名匹配。舞台、底座和默认模型预热，其余按候选标题加载；最多显示 8 个槽位，完整候选池可以通过滑动、前后按钮及候选列表浏览。原型共享几何、实例按槽位复用，原生资源随页面释放。系统关闭动画或用户关闭抽选动画时直接展示已保存结果；渲染失败仍保留列表与抽选操作。

## 轨迹

原生 LocationManager 兼容有无 GMS 的设备；同时请求精确/大致位置，用户在前台开始后使用 `location` 类型前台服务持续采样，不要求后台定位授权或电池白名单。5/10/100 米是采样偏好，同一会话保持不变。通知提供暂停和结束操作；拒绝通知权限不阻止前台启动录制。

采样点、当前指标和分段在同一事务写入同步队列。缓冲达到5点或2秒时保存，无新点时每10秒保存时长；只有成功提交才清除缓冲。时长使用单调时钟，暂停不累计。异常退出后将已保存的本设备录制恢复为暂停，不补造中断期间的位置。数据库替换、远端结束或删除会使旧录制会话失效。

分段复用 iOS 的100米精度过滤、80米/秒速度上限、五点窗口中值平滑及短段合并规则；显式暂停前后分别分析。WGS84距离使用Vincenty算法，对照容差为每段5厘米、速度0.001米/秒。人工编辑支持类型、采样点拆分、邻接边界和前后合并，保留手动段并检查旧版本；不把明确中断合并为连续路线。

回放支持进度、1/2/4倍速、跟随和当前采样数据，播放状态不改写原始记录。洞察由同一组点和分段生成运动汇总、停留点及质量问题，列表与地图联动。关联账单/日记按首末采样时间查询，不写入手工绑定。底图失败时保留路线及定位记录；删除轨迹为主记录、采样点和分段生成墓碑，保留关联账单和日记。

## 首页、历史与统计

首页按日常记录和生活工具分组，入口可见性只影响首页。进行中的轨迹独立显示时长与采样数；待打卡及七日内纪念日摘要在回前台和跨日时刷新。

统一历史从统计进入，包含轨迹、抽选、物品、打卡明细、账单、饮食日、日记和纪念日。按时间及跨模块稳定 ID 倒序分页，列表投影不读取图片二进制。周历角标统计当天全部历史，不跟随工具及搜索过滤；饮食按用餐日期分组和筛选，以当日条目与日标题的最近编辑时间排序。

删除确认捕获记录版本和子记录范围，再分派到业务仓库；打卡只删除指定明细，饮食日删除当天条目与日标题，轨迹连带点和分段，账单余额与同步墓碑按原规则处理。批量操作逐项提交并报告失败项，变更后的记录需要重新确认。

统计包含总条数、今日条数、有记录天数、最近时间、八工具使用和七日趋势。打卡按任务及计划日期计数，饮食按本地日期合并；统计不受首页隐藏设置影响。各来源独立观察，失败时保留其他区块和上次结果；聚合与排序在后台执行。账单五指标、收支趋势和分类占比共用金额规则，图表提供可朗读数值。

## 性能采集

`baseline` 和 `benchmark` 都使用独立包名 `imehc.recoow.benchmark` 与测试签名，不参与正式分发。`baseline` 保留类名以采集 Profile，`benchmark` 使用与 Release 相同的 R8 和资源压缩设置。性能测试最低 API 为 28。

每个性能测试类在测量前清空这个独立包的数据，再通过真实仓库准备 120 条账单、各 40 条日记/饮食/物品、各 12 条打卡/纪念日、10 个抽选候选和 600 个轨迹点。日期相对测试当天生成，提醒与同步保持关闭。准备入口仅存在于这两个测试变体，准备过程不计入采集区间，也不写入 Profile。

```sh
./gradlew :benchmark:connectedBaselineAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=imehc.recoow.benchmark.CoreBaselineProfile
./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=imehc.recoow.benchmark.CoreStartupBenchmark
./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=imehc.recoow.benchmark.CoreJourneyBenchmark
```

采集结果位于 `benchmark/build/outputs/connected_android_test_additional_output/`。将审核后的业务及启动规则去重合并到 `app/src/profiles/baseline-prof.txt`，启动规则同时放入 `app/src/profiles/startup-prof.txt`；随后重新构建 Release/benchmark，AGP 会重写 Profile。测试以 `BaselineProfileMode.Require` 确认安装的 Profile 可用。

审核后的应用规则仅由 release/benchmark 变体消费，baseline 采集变体不打包这些规则。首次从 benchmark 切换到 baseline 采集时，先卸载独立包 `imehc.recoow.benchmark`，清除设备上既有的参考 Profile；再运行上述采集任务。此操作会删除该性能包的合成数据，不涉及正式应用。不要将已有 Profile 被重新导出的结果当作新的启动覆盖。

真机运行前确认 ProfileInstaller 在应用强停后仍能接收测试广播。若出现安装 Profile 或清 Shader 缓存广播未收到，先排查厂商后台限制；该轮结果无效，不能通过关闭 `Require` 或忽略错误生成性能结论。

全功能采集包含三个 Tab、设置选择器、统一历史、八工具列表/详情、账单编辑、3D 浏览和抽选、轨迹详情。宏基准分别记录有/无 Profile 冷启动、账单/历史列表帧耗时与峰值内存、3D 浏览/抽选帧耗时与峰值内存。

API29+ 的 3D 首帧指标取本轮第一次场景创建到已加载场景首个渲染帧提交的耗时；不累加从结果页返回时的场景重建，也不等同于屏幕实际呈现时间。原始 Perfetto 和 JSON 保留在构建输出中。测量收益及设置回归阈值须使用真实设备；模拟器仅验证采集和打包链路，运行时需显式添加 `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`。
