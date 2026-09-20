# recoow UI 设计规范

本文件是项目后续 UI 新增、调整和验收的统一依据，适用于 `ios/recoow/` 的首页、统计、设置、功能页、详情页和 Sheet。文件名按项目约定保留为 `DESGIN.MD`。

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

新增 UI 必须先复用这些入口。不要在单个页面复制一套颜色、字号、圆角或顶部偏移。经确认的规范变更应同时更新共享实现和本文件；发现两者不一致时应修正差异，而不是建立第三套局部规则。

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

- 首页使用 `ScrollView`，统计和设置使用原生 `List` / `Form`。原生分组头自身占据布局空间，所以顶部参数不应机械地全部设成 12 pt；目标是最终可见位置一致。
- 统计根页使用 `.appGroupedListStyle(topContentMargin: AppDesign.tabContentTopMargin)`；设置根页使用对应的 `.appFormStyle(topContentMargin: AppDesign.tabContentTopMargin)`。
- 普通子页和 Sheet 使用容器样式的默认边距，不无条件套用 Tab 根页的参数。
- 首页分组标题到卡片间距为 `HomeStyle.cardSpacing`（10 pt）；入口行水平内边距 14 pt、垂直内边距 12 pt，图标到文字间距 12 pt。
- 不额外叠加空白顶部 Section、页面介绍区域、装饰横幅或大标题占位。
- 原生列表保留系统分组形状和系统控件布局，自绘卡片使用共享圆角；不要为了模拟系统列表再套一层卡片。

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
- SF Symbols 复用 `AppIconTileView`，默认实色底板和单色符号。不要各页面自行实现不同透明度的同类图标。
- 图标尺寸按场景引用 `AppDesign`：表单 32、紧凑 34、入口行 38、列表 44、历史 48、统计指标 26 / 28 pt。
- 点击目标最小为 44 × 44 pt；图标本身较小时扩大点击区域，而不是随意放大图标。
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
