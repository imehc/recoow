# recoow UI 设计规范

本文件是项目后续 UI 新增、调整和验收的统一依据，适用于 `ios/recoow/` 与 `android/` 的首页、统计、设置、功能页、详情页和 Sheet。文件名按项目约定保留为 `DESGIN.MD`。两端遵循相同的信息层级、色彩角色、密度和交互边界，具体实现分别使用平台原生组件。

## 1. 已确认的方向与本轮调整

- 以当前已确认的首页为视觉基准：清爽、紧凑、克制的日记与记录类应用，不采用营销型页面设计。
- 首页保留「日常记录 / 生活工具」分组；功能入口使用横向列表，不恢复大面积双列入口卡片、巨大标题或单独占一行的装饰性日期。
- 主题色取自应用图标的蓝色，统一用于 Tab 选中态、导航操作、主要按钮和选择状态；功能图标保留有辨识度的语义色。
- 统计和设置与首页统一导航标题模式、首屏顶部节奏、分组标题、列表标题及辅助文字字号。
- 全应用字号以现有功能页的系统语义字体为共同基准，Tab 页不再使用单独偏小的分组和说明字号；统计概览复用日记、账单、饮食页的指标组件。
- 其他页面与 Sheet 复用中性背景、紧凑列表、实色图标和统一容器样式；统计卡片不使用渐变、大色块边框或夸张数字。
- 本轮调整只涉及 UI 和信息呈现，不改变数据结构、计算规则、保存行为、同步、权限流程或导航目的地。

## 2. 统一实现入口

| 入口 | 职责 |
| --- | --- |
| `ios/recoow/App/Design/AppDesign.swift` | 字体、间距、圆角、图标尺寸、统计网格 |
| `ios/recoow/App/Design/AppPalette.swift` | 明暗模式颜色、语义色、图标前景色 |
| `ios/recoow/App/Design/AppViewStyle.swift` | 页面、导航、分组列表、表单、分组标题和 Sheet 样式 |
| `ios/recoow/Assets.xcassets/AccentColor.colorset/Contents.json` | 与应用图标一致的全局主题色 |
| `ios/recoow/Shared/Views/FormRowIconView.swift` | 统一图标底板、表单图标、图标按钮 |
| `ios/recoow/Shared/Views/MetricTile.swift` | 共享统计指标，`CompactSummaryMetricView` 用于功能页与统计页概览 |
| `ios/recoow/Features/Home/Views/HomeStyle.swift` | 首页专用排布，通用规格引用 `AppDesign` |
| `android/core/designsystem/src/main/kotlin/imehc/recoow/core/designsystem/RecoowTheme.kt` | Android Material 3 明暗色板、统一语义字号与功能入口语义色 |
| `android/core/designsystem/src/main/kotlin/imehc/recoow/core/designsystem/RecoowChrome.kt` | Android 紧凑顶栏、Tab 栏、分组标题 / 卡片、行式列表、指标网格、空状态与布局令牌 |
| `android/core/designsystem/src/main/kotlin/imehc/recoow/core/designsystem/RecordSkeleton.kt` | Android 首屏骨架屏（`RecordSkeleton`，经 `RecordLoadState` 用于各列表首帧） |
| `android/feature/home/src/main/kotlin/imehc/recoow/feature/home/HomeScreen.kt` | Android 首页：两段式行式入口，状态位放真实数据；方向已确认 |
| `android/feature/statistics/src/main/kotlin/imehc/recoow/feature/statistics/StatisticsScreen.kt` | Android 统计：2×2 概览网格、带标签的近七日柱状、占比条与打卡进度 |
| `android/feature/settings/src/main/kotlin/imehc/recoow/feature/settings/SettingsScreen.kt` | Android 设置：数据 / 显示 / 首页工具三段分组卡片，行内显示当前值与开关 |
| `android/feature/settings/src/main/kotlin/imehc/recoow/feature/settings/SettingsComponents.kt` | Android 设置子页共用件：`SettingsSection`（分组标题 + 容器）、`SettingsPrimaryButton`（46dp 主操作）、`SettingsBanner`（结果/错误条）、`SettingsProgress`（进行中任务） |

新增 UI 必须先复用这些入口。不要在单个页面复制一套颜色、字号、圆角或顶部偏移。经确认的规范变更应同时更新共享实现和本文件；发现两者不一致时应修正差异，而不是建立第三套局部规则。Android 页面不得绕过 `RecoowTheme` 在功能页定义局部主题色；iOS 页面继续使用 `AppDesign`、`AppPalette` 和 `AppViewStyle`。

## 3. 字体与信息层级

统一的是相同语义角色，不是让页面中所有文字一样大。首页、统计、设置、功能页、详情页与 Sheet 都遵循同一套系统语义字体；不得为 Tab 根页单独建立较大或较小的一套字号。

| 角色 | 实现 | 默认字号参考 |
| --- | --- | --- |
| 导航标题 | 系统导航栏，`.inline` | 由系统统一管理 |
| 分组标题 | 原生 `Section` 默认字体；自绘标题通过 `AppDesign.sectionTitleFont` 跟随相同系统规格 | iOS 26 为 17 pt；iOS 18 为 13 pt |
| 列表 / 设置入口标题 | `AppDesign.rowTitleFont`，`.body.weight(.medium)` | 17 pt |
| 正文 / 原生表单输入 | 系统 `.body` | 17 pt |
| 说明、内容摘要、入口状态 | `AppDesign.rowSubtitleFont`，`.subheadline` | 15 pt |
| 日期、计数说明、轻量元信息 | `AppDesign.metadataFont`，`.footnote` | 13 pt |
| 概览指标名称 | `AppDesign.summaryTitleFont`，`.caption` | 12 pt |
| 概览指标数值 | `AppDesign.summaryValueFont`，`.subheadline.weight(.medium)`，需要时 `.monospacedDigit()` | 15 pt |
| 列表行的数量 / 金额 | 与行标题一致的 `AppDesign.rowTitleFont` | 17 pt |

- Android 使用 `MaterialTheme.typography` 的语义槽位跟随系统字体缩放：`titleLarge` 对应导航标题，`bodyLarge` 对应列表 / 正文，`bodyMedium` 对应入口状态，`bodySmall` 对应日期和元信息；页面不直接写固定 `fontSize`。
- 表中的 pt 仅表示默认 Dynamic Type 下的参考值，实现必须使用语义字体，不能写成固定字号。
- 原生分组标题沿用系统 `Section` 规格；需要显式样式时使用 `.appSectionHeaderStyle()`。其字体与同一系统版本的默认分组标题一致，不将首页的自绘分组标题写成固定 15 pt。
- `.subheadline`、`.footnote` 等现有语义字体与相应共享 token 的字号完全一致，无须仅为改名批量重写功能页；新增自绘组件优先引用 `AppDesign`。
- 统计、日记、账单和饮食概览均复用 `CompactSummaryMetricView`，不得另写一套名称 / 数值字号；紧凑指标和普通列表行是不同角色。
- 标题使用 `AppPalette.ink`，辅助文字使用 `AppPalette.secondaryInk`。颜色或字重不能代替清晰的信息分组。
- 不在整个 `List` 或 `Form` 上粗暴设置标题字体，以免影响输入框、Picker、系统按钮和辅助文字。
- 不用 `.largeTitle`、加粗大数字、重复标题或大写英文小标签制造层级；图表内部小标题使用元信息字号和 `.medium`。详情内容自身的标题与普通列表行不是同级，不应通过缩放整个页面强行拉齐。
- Tab 栏标签沿用系统字号，不对某一个 Tab 单独缩放或加粗。

### 后续应用内字号设置

- 当前跟随系统 Dynamic Type，本轮不新增字号设置项、持久化字段或数据迁移。
- 后续如在设置页增加字号选项，应在应用公共根视图统一注入 `.dynamicTypeSize`，覆盖所有 Tab、导航子页和 Sheet；默认选项保留系统字号，不在各页面单独设置缩放值。
- 使用语义字体与根环境统一调节，不给每个页面单独传递数字字号，也不以 `.scaleEffect` 缩放整个界面。
- 不降低用户的无障碍字号；设计应用内字号选项时应保留系统辅助功能设置，并复用现有大字体单列、自动换行和大高度 Sheet 适配。

## 4. 间距与顶部对齐

顶部对齐以导航栏下方的首个分组标题及首组内容为准，不以屏幕顶部的绝对坐标为准。必须保留系统安全区，不能用负 `padding`、`offset` 或隐藏占位行掩盖差异。

| 规格 | 共享值 / 用法 |
| --- | --- |
| 页面水平边距 | `AppDesign.pagePadding` = 16 pt |
| 首页滚动内容顶部 | `AppDesign.pageTopPadding` = 12 pt |
| 统计 / 设置根页原生分组内容顶部边距 | `AppDesign.tabContentTopMargin` = 2 pt |
| 其他原生列表 / 表单内容顶部边距 | `AppDesign.groupedContentTopMargin` = 8 pt |
| 分组间距 | `AppDesign.sectionSpacing` = 22 pt |
| 网格间距 | `AppDesign.gridSpacing` = 12 pt |
| 自绘内容最大宽度 | `AppDesign.contentMaxWidth` = 680 pt |
| 自绘卡片圆角 | `AppDesign.cornerRadius` = 14 pt |
| 图标 / 小型指标底板圆角 | `AppDesign.iconCornerRadius` = 10 pt |
| Sheet 圆角 | `AppDesign.sheetCornerRadius` = 24 pt |

- Android 通过 `RecoowLayout` 管理：页面边距 16dp、组间距 22dp、组标题到卡片 8dp、行最小高度 56dp、卡片圆角 14dp、图标 23dp、分隔线起点 52dp。入口行左右内边距 14dp、上下 9dp，图标到文字 14dp；分隔线避开图标并与文字左对齐。字号增加时行高自然增长，状态文字允许换行。
- Android 组间距只有一个来源：`RecoowPage` / `RecordPage` 的 `spacedBy(RecoowLayout.sectionGap)`，页面不再各自传间距（早期 12 / 16 / 22 混用会导致同一屏节奏忽宽忽窄）。内容顶部留白统一 8dp，底部 24dp。
- Android 账单页把首屏让给列表：概览默认只显示「本月支出 / 本月收入」两格，展开与收起是分组标题右侧的文字动作；搜索收在顶栏搜索图标里，点开才插入输入框并自动聚焦。
- Android 顶栏统一使用 `RecoowCompactTopBar`（标题 20sp 半粗、最小高度 48dp、系统状态栏安全区独立保留），Tab 根页、设置子页与功能页共用 `RecoowPage` 骨架，不再出现第二套顶栏或第二套首屏节奏。日期并入首个可见分组的标题行，字体缩放 ≥1.5 时标题与日期分行。
- Android 分组卡片使用 `RecoowGroup`（表面色 + 1dp 发丝线，不用阴影）；行式列表统一为 `RecoowRow` / `RecoowToolRow` / `RecoowSwitchRow`，只有跳转行显示箭头；概览指标使用 `RecoowMetricGrid`（默认 2×2，字体缩放 ≥1.5 退化为单列）；空状态使用 `RecoowEmptyState`，不给页面只留两行灰字。
- Android 列表与详情页不再使用悬浮按钮：新建统一为顶栏右侧的 `+` 图标（`RecoowAddAction`），次要操作（账户、分类、标签、历史等）收进顶栏的 `⋯` 下拉（`RecoowOverflowMenu`），顶栏最多两个图标。下拉容器用表面色 + 发丝线 + 12dp 圆角，与分组卡片同一套材质。
- Android 取值/筛选类控件用行式选择器 `RecordChoice`（标签在左、当前值 + 箭头在右），与设置页取值行同一读法，不再用整宽描边空按钮；权限类提示用 `RecoowNotice`（白卡 + 说明 + 文字动作），不使用另一种灰底卡片。
- Android 空状态左对齐、标题与正文同一档字号，与列表行起点对齐；不用居中大标题，避免被读成按钮。
- Android 空状态分两档，文案不能混用：「还没有数据」讲怎么开始（一句引导，只有引导不重复顶栏的 `+`）；「没有符合条件的记录」必须同时给出原因和出口——说明「换个关键词，或者清空筛选条件再看看」并提供「清空筛选」文字动作（`RecoowEmptyState(actionLabel/onAction)`），清空后连带收起搜索框与筛选面板。没有数据的列表不能只留一片空白。
- Android 表单校验走行内报错：字段描边与文字染红、下方给出具体原因（`RecordField(error)` / `RecordChoice(error)` / `ChoiceField(error)`），顶栏保存按钮不再灰着不解释；点保存时若表单不合法，第一次点击只负责把错误标出来，不提交。金额这类相邻字段的错误挂在真正缺的那一格上（缺原价就标原价），不要用一句笼统的「请输入有效金额」盖住整组。
- Android 列表底部统一 `RecordListFooter`：追加中复用骨架、失败给「重试」、还有下一页给「加载更多」行、到底给一行居中的「已经到底了」（`record_end`）。各页面不再各自写「加载更多」按钮或裸错误文字。
- Android 首屏加载用骨架屏 `RecordSkeleton`（行高、内边距、分隔线缩进与真实行一致：可选图标位 + 标题条 + 副标题条 + 行尾取值条），整屏共用一次 900ms 明暗呼吸，不做逐块流光；不再用居中转圈占位。已有内容后的刷新仍用顶部细进度条，不能换成骨架，避免每次刷新整屏闪一下。
- Android 三维场景与地图这类自绘面板统一放在 14dp 圆角 + 1dp 发丝线的容器里（`Modifier.clip` + `border`），不裸铺在页面底色上；面板自身的离线/失败提示用 `RecoowNotice`，不再用裸文字加文字按钮。
- Android 详情页的操作统一沉到底部：主操作是 46dp 高、12dp 圆角的整宽按钮，删除是红字行，编辑/定位等次级操作是带箭头的行；顶部 `⋯` 只放页面级动作（历史、编辑集合、删除集合）。
- Android 三个 Tab 共用 `RecoowTabBar`：中性表面色、0.5dp 顶部分隔线、最小内容高度 60dp、23dp 图标、12sp 语义标签；选中图标与文字使用品牌蓝，无彩色胶囊和独立阴影。系统底部导航安全区单独保留，大字体允许栏高增长，保留原有 Tab 显隐、选中语义与导航状态。
- Android 功能页列表统一「分组标题 + 分组卡片 + 行式条目」：账单按日期分组、条目双行（标题 / 分类·支付·时间）并用右侧金额代替整块卡片；日记多一行正文摘要；饮食按餐别分组；详情页把字段压成 label 右值行，删除等破坏性操作单独成组并只给红色标题。分隔线缩进跟随有无图标（有图标 52dp，纯文字 14dp）。
- Android 设置子页（云同步 / 数据管理 / 应用更新 / 素材库）与功能页同一套节奏：分组标题 + 分组卡片 + 行，行内左标题右取值；主操作是整宽 46dp、12dp 圆角按钮并紧跟在相关分组之后，破坏性操作（重置、断开、覆盖导入）只给红色标题行，不进主按钮。进行中的任务用细进度条卡片（`SettingsProgress`），结果与错误用同材质提示条（`SettingsBanner`，错误才染成 error 色），不再用裸文字或第二套灰底横幅。
- 可切换视图、刷新列表这类「非跳转」动作放到分组标题右侧的文字动作（`RecoowSectionHeader(onTrailingClick)`），列表为空时不显示无意义的切换入口；需要二次确认的危险动作一律走对话框，同行内只保留入口。
- Android 更新说明按发布用的轻量 Markdown 渲染：`#` 起行作为小节标题、`-` 作为项目符号，其余按正文；不引入完整渲染器，也不把 `##` 原样显示给用户。
- Android 素材库沿用同一卡片材质（表面色 + 1dp 发丝线 + 14dp 圆角）：图片只在上方裁圆角，标题一档正文、格式与尺寸一档元信息，每张图的操作收进 `⋯` 下拉，不再每张卡片铺四个文字按钮。
- iOS 首页使用 `ScrollView`，统计和设置使用原生 `List` / `Form`。原生分组头自身占据布局空间，所以顶部参数不应机械地全部设成 12 pt；目标是最终可见位置一致。Android 使用 Compose `LazyColumn`、`Scaffold` 和 Material 3 分组组件实现相同层级。
- 统计根页使用 `.appGroupedListStyle(topContentMargin: AppDesign.tabContentTopMargin)`；设置根页使用对应的 `.appFormStyle(topContentMargin: AppDesign.tabContentTopMargin)`。
- 普通子页和 Sheet 使用容器样式的默认边距，不无条件套用 Tab 根页的参数。
- 首页分组标题到卡片间距为 `HomeStyle.cardSpacing`（10 pt）；入口行水平内边距 14 pt、垂直内边距 12 pt，图标到文字间距 12 pt。
- 不额外叠加空白顶部 Section、页面介绍区域、装饰横幅或大标题占位。
- 原生列表保留系统分组形状和系统控件布局，自绘卡片使用共享圆角；不要为了模拟系统列表再套一层卡片。
- 账号页同步状态与「立即同步」在同一行展示，文字起点与其他行一致，空间不足时自然分行；进度、上次同步和待同步数量放在「同步详情」。同步、设备管理、冲突处理与退出仅在「账号与云同步」保留入口，连接设置只承载服务器、绑定信息及云端清空操作。
- 账号读取失败、操作结果及能力提示放在相关分组 footer，复用 `FormFeedbackView`；图标与正文间距复用 `metadataItemSpacing`，提示与重试按首行文字基线对齐、没有列表行分隔线。重试保留已有账号内容和提示，加载指示在图标原位置展示，不插入额外加载行；大字体下重试移到说明下方，保持 44 pt 点击目标且不撑高正常列表行。

## 5. 颜色规范

### 基础色

| 用途 | 浅色 | 深色 |
| --- | --- | --- |
| 页面背景 `background` | `#F4F5F7` | `#111214` |
| 自绘卡片表面 `surface` | `#FFFFFF` | `#1F2024` |
| 主文字 `ink` | `#1C2026` | `#F3F4F6` |
| 次级文字 `secondaryInk` | `#696F79` | `#A7ABB4` |
| 主题色 `accent` | `#3E70E3` | `#98B3FF` |
| 主题色实底前景 `accentForeground` | `#FFFFFF` | `#141A2C` |

主题色来自应用图标的蓝色。实现通过 `AppPalette.accent` 读取 `AccentColor`，不要在页面重新写十六进制值，也不要将 `AppPalette.blue` 或系统 `.blue` 当成全局主题色。

### 功能与状态色

| Token | 浅色 | 深色 |
| --- | --- | --- |
| `green` | `#067E5B` | `#16A87B` |
| `teal` | `#087B92` | `#159DB6` |
| `blue` | `#2868DA` | `#4588EF` |
| `lavender` | `#8250CD` | `#A77BEC` |
| `rose` | `#C5366F` | `#E6689B` |
| `amber` | `#9B6109` | `#C2892A` |
| `clay` | `#B85E1A` | `#D57E33` |
| `red` | `#CB3345` | `#F06672` |
| `neutral` | `#677180` | `#737E8F` |

- 功能入口优先复用 `ToolRoute.tint`。语义色用于辨识功能或状态，不用于给整页铺色。
- 保留明确的成功、进行中、警告、支出、收入和删除语义；删除不为统一主题而改成蓝色。确认弹窗等系统 destructive 角色照常使用；但「更多」菜单内的删除项不单独着色，标题和图标与同菜单其他项一样走系统默认色，避免 `role: .destructive` 让图标与标题分成两种颜色。
- 主要交互、选中勾选、筛选状态和 Tab 选中态使用主题色；次要信息不能靠极低透明度呈现。
- 主题色实底按钮和图标使用 `AppPalette.accentForeground` 或 `AppPalette.iconForeground(for:)`，不能在深色模式下永远使用白色前景。
- 系统列表表面、分隔线、禁用态和导航材质可保留系统自适应表现；不要用不透明覆盖层破坏原生交互。
- 禁止装饰性渐变、发光、厚重投影、全页低饱和粉彩、多色描边卡片；必要状态色不属于装饰。

## 6. 列表、卡片与图标

- 优先「图标 + 标题 / 辅助信息 + 真实操作或状态」的横向排布，相关条目合并为一个分组。
- 同一信息不要重复包装成卡片、标签和横幅；不添加欢迎语、宣传口号、引导购买或虚构活跃数据。
- 分隔线避开图标区域，与正文起点对齐；只在有跳转的行显示箭头。
- 统计指标使用中性底板、小图标和紧凑数值；默认两列，无障碍大字体通过 `AppDesign.metricColumns(for:)` 切换一列。
- 时间、数量、金额等频繁变化的数值按需使用等宽数字；概览指标使用共享的 15 pt 语义字号，列表行数值与行标题同级，不为突出指标额外扩大字号。
- iOS SF Symbols 复用 `AppIconTileView`，默认实色底板和单色符号。不要各页面自行实现不同透明度的同类图标。
- 图标尺寸按场景引用 `AppDesign`：表单 32、紧凑 34、入口行 38、列表 44、历史 48、统计指标 26 / 28 pt。
- iOS 点击目标最小为 44 × 44 pt，Android 为 48 × 48dp；图标本身较小时扩大点击区域，而不是随意放大图标。
- 有用户照片或媒体内容时优先呈现真实内容；无图片使用对应功能图标，不使用装饰插画填充空白。
- 标签和状态短文本使用简单中性底板或语义色，不叠加多层胶囊、边框与阴影。
- 地图、照片预览、图片裁剪等内容画布保留适合内容的布局，不强制套上列表卡片。

## 7. 页面与 Sheet 的接入方式

### 页面

- 普通页面使用 `.appPageStyle()`；仅需导航配置时使用 `.appNavigationStyle()`。
- 分组列表使用 `.appGroupedListStyle()`；表单使用 `.appFormStyle()`，不要再单独叠加另一套背景或顶部留白。
- 导航标题保持 `.inline`，新增、筛选、历史等操作放在系统工具栏，不在正文重复制作导航栏。
- 保留现有 Tab 显隐、返回、深链和导航目标，不为排版修改交互路径。

Tab 根页接入示例：

```swift
List {
    Section {
        content
    } header: {
        Text("概览")
            .appSectionHeaderStyle()
    }
}
.appGroupedListStyle(topContentMargin: AppDesign.tabContentTopMargin)
.navigationTitle("统计")
```

### 选什么的场景页面

- 候选页使用非 AR 的 3D 内容画布，保留原生导航。抽选按钮放在转盘中心，并保留原生按钮的可访问性和最小 44 pt 点击区域。
- 底部抽屉显示当前候选、候选列表和历史记录；列表独立滚动，展开时场景在剩余区域重新构图，不将场景推离屏幕。候选数量与展开箭头同行排列。
- 拖动与点击模型用于浏览，快速甩动或中心按钮触发原有权重抽选及自动保存；动画不改变概率。减少动态效果时直接揭晓，页面不可见时停止动画。
- 场景与候选数量分离，使用有限可见位置循环呈现完整候选池；未知或尚未加载的模型显示默认立牌。
- 集合页提前准备空场景、底座和默认模型，食物模型按需加载并缓存。原生文字、照片详情、表单及确认弹窗继续使用共享样式和系统语义字号。
- iOS 运行时资源统一放在 `ios/recoow/Resources/DecisionModels/`，USDZ 和清单纳入 Git，普通构建无需 Blender；更新模型时运行 `make decision-models-ios` 重新导出。GLB、Blender 源文件与生成脚本放在仓库根目录下的 `assets/decision-scene/` 并纳入版本管理，供 iOS 与后续 Android 共用。生成命令放在根目录 `README.md`，详细文档放在 `docs/decision-scene/`，新增 Markdown 仅保留本地。

### Sheet

- 使用 `.appSheet(isPresented:onDismiss:content:)` 或 `.appSheet(item:onDismiss:content:)`，不要在新页面重复编写通用 Sheet 外观。
- 共享样式负责主题色、背景、24 pt 圆角和拖拽指示条；Sheet 内容继续使用统一的列表或表单样式。
- 需要标题和操作栏的 Sheet 保留 `NavigationStack`；取消在前、保存 / 完成在后，保持已有校验、禁用和关闭行为。
- 短选择器可保留原有中等或固定高度，长表单保留原有大高度；不要将所有弹层强制为同一种高度。
- 无障碍大字体下优先大高度，避免筛选项和主要操作被截断；内容必须能滚动。
- 系统权限弹窗、文件选择、确认对话框保留系统实现，不替换成装饰性自定义弹窗。

```swift
.appSheet(isPresented: $isShowingEditor) {
    NavigationStack {
        EditorFormView()
    }
}
```

## 8. 自适应、文案与交互边界

- 支持浅色、深色和 Dynamic Type；长标题、日期及中文 / 英文不能靠强制缩小字体才能显示。
- 首屏间距对比必须在相同设备、语言和字号下进行，并同时检查首页、统计和设置，不能孤立调其中一页。
- 字体增大时优先换行、纵向排布和单列统计，不使用固定行高裁掉正文。
- 首页分组标题与日期在空间充足时同排，空间不足时通过 `ViewThatFits` 改为上下排布，不能让日期挤压标题。
- 图标按钮要有可访问性名称；装饰图标不重复朗读，状态不能仅由颜色表达。
- 正文及关键辅助文字应满足可读对比度，至少按普通文字 4.5:1、关键图形 3:1 检查；必要时使用主文字色承载信息，不依赖主题色小字。
- 所有文案走现有本地化机制；只使用功能名称、真实状态和必要说明，不添加营销内容。
- 仅为验证 UI 使用的预览数据、启动参数和调试入口不得进入生产代码。
- 视觉调整不得改变模型、数据库、迁移、业务计算、保存 / 删除、通知、录音、定位或同步逻辑。

## 9. 后续扩展的验收清单

- [ ] 已阅读本文件并复用 `AppDesign`、`AppPalette` 和现有样式入口。
- [ ] 同级文字在三个 Tab、功能页、详情页和 Sheet 中具有相同的字号、字重和颜色角色，而不只是 Tab 之间一致。
- [ ] 统计与日记 / 账单 / 饮食的概览使用同一指标组件，字号随全局 Dynamic Type 一起变化。
- [ ] 首个分组标题与首组内容的顶部节奏和首页一致，没有额外大标题留白。
- [ ] 导航、Tab、选择状态及主要操作使用应用图标对应的主题蓝色。
- [ ] 分组、列表、统计卡片及 Sheet 不新增渐变、营销文案或装饰性大卡片。
- [ ] 已检查浅色、深色、大字体、长内容及空状态；主要内容和操作不被键盘、Tab 或 Sheet 裁切。
- [ ] 原有功能可见性、导航、取消 / 保存、状态变化和业务数据保持不变。
- [ ] 已运行必要的编译 / 检查；未验证的设备或交互明确说明，不将截图检查等同于完整功能测试。
- [ ] 如确需改变全局风格，已先确认方向，再同步更新共享实现与本文件。
