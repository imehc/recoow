# 项目协作指南

本文件适用于整个仓库。进入具体模块后，先阅读该模块的 README 和适用的局部协作规范。

## 项目结构

| 目录 | 技术与职责 |
| --- | --- |
| `ios/` | SwiftUI 客户端；`App` 为应用入口，`Features` 为功能，`Core` / `Shared` 为基础能力与共享组件 |
| `android/` | Kotlin、Compose、Room；`app` 负责装配与导航，`feature` 负责界面，`core` 负责共享能力 |
| `backend/` | Go、PostgreSQL；按 handler、service、repository 分层 |
| `web/` | SvelteKit、TypeScript；云端只读工作台 |
| `openapi/` | 公共接口与同步契约 |
| `scripts/`、`tests/` | 生成、版本与发布工具及其回归测试 |
| `assets/` | 跨端共享资源；构建与部署规则见根 Makefile、`deploy/` |

## 开发约定

- 修改前检查 Git 状态，保留已有改动；只处理当前任务涉及的文件。
- 沿用模块现有分层和共享实现。Android feature 不互相依赖、不直接访问存储，domain 不依赖平台框架。
- 新增或修改的手写注释默认使用中文，说明原因、边界和非显然的约束，不复述代码；标识符、协议字段及工具要求的格式保留英文，上游与生成代码保留原样。
- 公共接口先改 `openapi/openapi.yaml`，再用根 Makefile 生成并更新各端适配；不手改生成产物。
- 数据结构变更提供对应迁移，不修改已发布迁移或使用破坏性迁移。保留未知字段、缺省与显式空值、整数金额和时间精度。
- 不将凭据、本机配置或用户数据写入代码、日志与 Git。各模块独立升版，遵循 [VERSIONING.md](VERSIONING.md)。

## UI 变更

- 修改 iOS、Android 的页面、组件、导航或 Sheet 前，阅读 [DESGIN.MD](DESGIN.MD)；Web 同时遵循 [web/DESIGN.md](web/DESIGN.md)。
- 复用各端设计系统，保持首页、统计、设置及功能页、详情页、Sheet 的同级信息一致。
- UI 任务只调整呈现层，保留业务规则、数据、导航目的地和已有操作行为。
- 修改全局样式前先确认方向，再同步更新共享实现和设计文档；任务中已明确的方向不重复确认。

## 文档维护

- 当前规范以本文件、设计文档及模块 README 为准；历史方案仅作背景，不沿用其中过时的约束或待确认事项。
- 项目调整形成可复用经验时，更新最接近的现有文档：协作约定写入本文件，设计规则写入设计规范，模块用法与稳定限制写入模块 README。只记录最终规则、适用条件和必要原因，不追加任务流水账或重复文档。

## 验证

按改动选择必要检查；构建依赖与完整命令见各模块 README 和根 Makefile。

| 范围 | 常用检查 |
| --- | --- |
| 公共契约与脚本 | `make check-generated`；`python3 -m unittest discover -s tests -t .` |
| 后端 | 在 `backend/` 运行 `go test ./...`、`go vet ./...` |
| iOS | 根目录 `make ios-build`；按改动运行相关测试 |
| Android | 在 `android/` 运行 `./gradlew :app:assembleDebug`，以及相关模块测试与 lint |
| Web | 在 `web/` 运行 `pnpm verify` |

UI 另按设计文档检查实际显示与交互。在对话中报告已执行检查和未覆盖环境，不将截图检查当作完整功能测试。

## Git 提交

- 格式为 `类型(scope): 中文说明`，例如 `feat(android): 添加记录入口`、`fix(ios): 修正列表布局`。
- scope 按平台或仓库模块归类，优先使用 `android`、`ios`、`backend`、`web`、`ci`、`release`；不按业务功能另建 scope。
- 跨平台改动按平台拆分提交；公共契约及后端协议归入 `backend`，各端实现与测试归入对应平台。仓库公共文档可省略 scope，如 `docs: 更新项目协作指南`。
- 测试通过情况、编译结果及未验证环境只在对话中报告，不写入提交说明或受版本管理的文档。
- 临时计划、验收报告和构建日志仅保留在本地忽略目录，除非用户明确要求。必要测试代码、测试向量和长期维护的运行指令可以提交。
