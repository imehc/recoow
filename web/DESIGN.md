---
name: recoow Web
description: 统一、清晰的记录工作台
colors:
  primary: '#1668dc'
  primary-soft: '#eaf2ff'
  background: '#f5f7fa'
  foreground: '#1f2329'
  card: '#ffffff'
  muted: '#f0f2f5'
  muted-foreground: '#646b76'
  border: '#e5e7eb'
  input-border: '#cbd0d8'
  success: '#18794e'
  success-soft: '#edf8f1'
  danger: '#c03635'
  danger-soft: '#fff1f0'
  warning: '#876114'
  warning-soft: '#fff8e7'
  dark-background: '#141414'
  dark-foreground: '#ededed'
  dark-card: '#1f1f1f'
  dark-primary: '#69a9ff'
  dark-primary-soft: '#182c46'
  dark-muted: '#292929'
  dark-muted-foreground: '#a3a3a3'
  dark-border: '#363636'
  dark-input-border: '#525252'
  dark-success: '#77c9a0'
  dark-success-soft: '#1c3529'
  dark-danger: '#ff9894'
  dark-danger-soft: '#3e2525'
  dark-warning: '#e9bf64'
  dark-warning-soft: '#3b321f'
typography:
  headline:
    fontFamily: '-apple-system, BlinkMacSystemFont, Segoe UI, PingFang SC, Microsoft YaHei, sans-serif'
    fontSize: '1.5rem'
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: '-0.015em'
  title:
    fontSize: '1rem'
    fontWeight: 600
    lineHeight: 1.5
  body:
    fontSize: '0.875rem'
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontSize: '0.8125rem'
    fontWeight: 500
  metadata:
    fontSize: '0.75rem'
    fontWeight: 400
  metric:
    fontSize: '1.5rem'
    fontWeight: 600
    lineHeight: 1.35
    letterSpacing: '-0.015em'
rounded:
  small: '4px'
  control: '6px'
  overlay: '8px'
  panel: '12px'
spacing:
  '1': '4px'
  '2': '8px'
  '3': '12px'
  '4': '16px'
  '6': '24px'
  '8': '32px'
components:
  button-primary:
    backgroundColor: '{colors.primary}'
    textColor: '{colors.card}'
    rounded: '{rounded.control}'
    height: '36px'
    padding: '0 14px'
  button-outline:
    backgroundColor: '{colors.background}'
    textColor: '{colors.foreground}'
    rounded: '{rounded.control}'
    height: '36px'
    padding: '0 14px'
  input:
    backgroundColor: '{colors.card}'
    textColor: '{colors.foreground}'
    rounded: '{rounded.control}'
    height: '36px'
    padding: '0 12px'
  panel:
    backgroundColor: '{colors.card}'
    rounded: '{rounded.panel}'
  nav-active:
    backgroundColor: '{colors.primary-soft}'
    textColor: '{colors.primary}'
    rounded: '{rounded.control}'
    height: '42px'
    padding: '0 16px'
---

# Design System: recoow Web

跨端共同原则见 [DESGIN.MD](../DESGIN.MD)。本文件定义 Web 工作台的具体布局、字号与间距，不套用移动端的 pt / dp 规格。

## Overview

**Creative North Star: "清晰的工作台"**

采用传统后台的布局和交互习惯，让记录、筛选和变化详情容易扫描。品牌沿用客户端图标，界面以统一的字号、间距和状态色形成秩序。

**Key Characteristics:**

- 固定导航框架，内容独立滚动。
- 白色或中性炭灰内容面，蓝色表示操作与选择。
- 表格、筛选和详情使用同一套控件与留白。
- 文案简洁，分类与类型值沿用移动端名称。

## Colors

### Primary

工作台蓝用于主操作、当前导航、选中项与链接。浅色强调面承载选择状态，不将主色铺满内容区。深色使用较亮的蓝色以保证可读性；主按钮上的文字采用深色。

### Neutral

冷灰页面与白色内容面形成浅色层次；中性炭灰构成深色层次。正文、辅助文字、分隔线和输入框描边各有独立角色，颜色以 frontmatter 和 `src/app.css` 的语义变量为准。

### Semantic

成功色表示新增、恢复及修改后的内容；危险色表示删除、支出及修改前的内容；警告色用于离线等需要注意的状态。状态始终配合文字，不仅依赖颜色。

**The Semantic Color Rule.** 同一含义在所有页面复用同一语义颜色，主题切换只替换对应变量。

## Typography

全站复用系统 UI 字体栈。标题、正文和辅助信息形成清晰层级，避免页面单独定义字体体系。

页面标题使用 headline，区域标题使用 title，表格与字段正文使用 label 尺度，时间和辅助信息使用 metadata。数字与时间采用等宽数字；正文和差异内容允许换行，长 ID 可在任意位置断行。

窄屏页面标题降至 1.25rem；统计数字在中等及窄屏使用同一较小尺度。说明文本保持短句，段落最大宽度按内容用途约束在 65–75ch。

**The Shared Type Rule.** 相同层级复用语义字号，业务内容保持原文与精度。

## Layout

工作台占满视口高度，顶部栏和侧栏固定在框架内，仅主内容区纵向滚动。侧栏展开宽度 224px，折叠宽度 76px；工具栏高 64px。主内容最大宽度 1600px，常规留白 24px，组件内部多使用 16px 或 20px。

间距遵循 frontmatter 的 4/8px 尺度。相关信息紧密排列，筛选、结果和详情区域之间保留明确间隔。记录目录用分类入口连接子类型，结果页以子类型标签切换；历史下拉框复用分类分组。

在 1200px 以下，筛选和目录减少列数，详情侧栏缩窄；900px 以下侧栏由抽屉替代，详情与概览改为单列；600px 以下工具栏高 56px，主内容左右留白 16px，表格转为带字段标签的列表，差异内容上下排列。

**The Stable Frame Rule.** 主内容滚动不带动工具栏与侧栏；页面跳转后内容回到顶部。

## Elevation & Depth

常规面板使用细边框分隔，无阴影。菜单、日期浮层及导航抽屉用偏移且柔和的阴影表示浮层关系，具体阴影由 sidecar 的 extensions 记录。避免给普通面板同时叠加边框与宽阴影。

状态切换使用共享缓动；抽屉采用短暂的位移与淡入。减少动画偏好下缩短或关闭动画，并保持内容可见。

## Shapes

面板使用圆角矩形，控件圆角小于面板，浮层介于两者之间。圆形仅用于头像和轻量状态图标。内容面保持简洁，不增加装饰边条或仿物理材质。

## Components

### Buttons

主按钮、次按钮与图标按钮复用 shadcn-svelte Button。普通按钮高 36px；登录表单控件高 42px。次操作使用描边或 ghost，禁用状态降低强调并改变光标；所有键盘操作保留可见焦点。

### Inputs / Fields

搜索框、下拉框和日期触发器统一高度、描边、圆角与内边距。输入文字跟随主题，光标、选择色和滚动条使用语义变量。焦点使用主色，禁用状态使用中性背景。

下拉、日历、菜单、抽屉和展开区域复用 Bits UI，提供键盘操作、关闭和焦点返回。日期组件提供清除按钮，结束日期包含当天；避免浏览器原生下拉框和日期选择器的独立外观。

### Cards / Containers

内容面板复用 panel 的边框与圆角。标题行与内容用轻分隔线衔接。嵌入历史快照的媒体使用无外层面板的嵌入形式，避免嵌套面板。

### Navigation

主导航固定在左侧，当前项使用主色强调面；进入记录后展示客户端的大分类。折叠时隐藏文字，保留图标及可访问名称；窄屏使用抽屉，导航完成后关闭。主题、语言与账号菜单放在工具栏右侧。

品牌与 favicon 复用客户端 App 图标，按当前主题选择浅色或深色资源。界面语言和主题的自动选项统一叫「跟随系统」。

### Data / Details

桌面使用紧凑表格，手机使用带标签的列表行。记录标题优先显示，ID 和版本次要显示；已加载数量与继续加载操作放在结果区域。

历史详情先显示字段差异，再显示修改时间、云端接收时间和来源。历史快照与技术来源信息可展开；长内容提供展开与收起，保留全文。类型值使用客户端文字映射，未知值保留原文。金额、空值与缺失值保持独立含义。

### Feedback

加载显示骨架，空状态提供简短提示，失败提供重试，离线提供必要反馈。成功登录后的常规框架不重复展示云端、只读和在线说明；登录页保持表单所需内容。

## Do's and Don'ts

### Do:

- **Do** 复用语义颜色、共享控件和间距尺度。
- **Do** 分类与枚举值沿用客户端叫法，保留未知数据。
- **Do** 保持工具栏与侧栏固定，并提供窄屏和键盘操作。
- **Do** 保留完整内容、精确金额和修改与接收双时间。

### Don't:

- **Don't** 为同类控件引入浏览器原生下拉、日期或独立外观。
- **Don't** 增加重复云端说明、冗长操作文案或标题上方的小标签。
- **Don't** 给普通面板叠加阴影，或另建局部主题与字号体系。
- **Don't** 用未经识别的枚举值猜测已知含义。
