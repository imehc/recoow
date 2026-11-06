---
version: 1
slug: 'src-routes-layout-svelte'
primary_target: 'src/routes/+layout.svelte'
related_targets:
  [
    'src/routes/+page.svelte',
    'src/routes/records/+page.svelte',
    'src/routes/history/+page.svelte',
    'src/routes/history/[eventId]/+page.svelte',
    'src/routes/records/[table]/+page.svelte',
    'src/routes/records/[table]/[id]/+page.svelte',
    'src/routes/devices/+page.svelte',
  ]
---

# Web 云端工作台

Mode: operate. Scope: Web 全部只读页面与共享控件。

## Direction contract

THESIS: 用户确认传统后台工作台；布局、密度、交互与 Ant Design 类后台的习惯一致，查阅记录和追溯变化先于装饰。保留全部只读操作。用户要求移除重复云端、只读、在线说明，品牌和站点图标与客户端一致。

OWN-WORLD: 浅色白色内容面、冷灰页面背景、蓝色选择与主操作；深色使用中性炭灰。统一系统字体、4/8px 间距尺度、36px 控件、紧凑表格与低强调分隔线。

STORY: 左侧选择工作区；页面顶部筛选，主体直接读数据；打开详情后先看内容差异，再看修改与接受时间和设备来源。空、错、离线、加载均有明确反馈。

FIRST VIEWPORT: 224px 可折叠侧栏、64px 工具栏；主体 24px 留白，标题与筛选排列清楚。概览以一行四项统计、分类分布和最近变化为主体。记录和历史将筛选与表格分成两个面，移动端改为抽屉导航与有标签的列表行。变化详情采用内容和来源双栏；双时间并列是产品的识别动作，移动端顺序保持一致。

FORM: 用户直接选择的类别标准，覆盖随机方向建议；seed:b3b6d61adf6ce2b3。代码构建，不设图像构图轮。外部方向库不可用，未取得对照板。

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
