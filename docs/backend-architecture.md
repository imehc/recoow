# 后端架构

后端为移动端同步与 Web 管理提供统一服务。公共接口和字段定义见 [OpenAPI 契约](../openapi/openapi.yaml)，环境配置见 [后端说明](../backend/README.md)，部署和数据迁移见 [部署文档](../deploy/README.md)。

## 分层与依赖

| 层 | 职责 |
| --- | --- |
| `cmd/api`、`cmd/worker` | HTTP 服务与后台维护入口 |
| `internal/app`、`internal/config` | 依赖装配与配置读取 |
| `internal/handler` | 路由、请求解析、鉴权和响应 |
| `internal/service` | 密码身份、同步修订、冲突处理、余额与业务投影 |
| `internal/domain` | 状态模型、仓储与事务接口 |
| `internal/repository` | PostgreSQL 持久化、sqlc 查询、内存仓储 |
| `internal/schema` | 从 OpenAPI 派生同步字段和兼容规则 |
| `migrations` | 带版本与校验和的数据库迁移 |

PostgreSQL 使用 pgx v5 的 `database/sql` 适配器。账号、设备、身份、会话与统计等固定查询由 sqlc 生成；动态业务字段使用契约白名单构建 SQL，标识符引用与值参数化分别处理，两类查询共用连接池和事务。

## 数据存储

21 个业务实体分别存储于结构化表：

| 业务 | 表 |
| --- | --- |
| 账单与储值 | `bills`、`stored_value_accounts`、`stored_value_adjustments` |
| 日记 | `diary_entries`、`diary_tags`、`diary_links` |
| 饮食 | `food_entries`、`food_day_records` |
| 提醒与纪念日 | `reminders`、`reminder_completions`、`anniversaries` |
| 决策 | `decision_collections`、`decision_options`、`decision_choice_records` |
| 物品 | `item_categories`、`stored_items` |
| 轨迹 | `tracks`、`track_points`、`track_segments` |
| 媒体元数据 | `media_assets`、`media_attachments` |

记录保留字符串 ID，金额使用整数分，业务时间使用毫秒整数。业务表中的 `tenant_id` 表示数据账号；储值调整中的 `account_id` 表示业务储值账户。

已知字段存储于业务列，`extras JSONB` 保存未知字段，`present_fields` 区分字段缺省与显式 null。`row_state` 是以调用者权限执行的快照视图，从业务列和扩展字段重建同步状态。

| 辅助存储 | 用途 |
| --- | --- |
| `account`、`devices` | 数据账号、恢复凭据和长期设备凭据 |
| `users`、`sessions` | 密码身份与可过期 Web 会话 |
| `change_log` | 按账号单调递增的修订历史和墓碑 |
| `mutation_receipts` | 请求摘要与幂等结果 |
| `sync_metadata` | 数据集世代与同步水位 |
| `media_objects` | 账号范围内的媒体对象索引 |

媒体字节按账号与 SHA-256 存放在文件系统，业务记录只保存媒体引用。数据库迁移与媒体迁移分别处理，两者没有共同事务。

## 账号隔离与身份

业务表、同步历史、回执、媒体索引及同步元数据开启强制 RLS。服务从设备 token 或 Web 会话解析数据账号，并在事务内设置账号上下文；请求体不能指定数据归属。业务依赖在同一账号范围内检查。

运行角色不能是超级用户、拥有 BYPASSRLS、拥有业务表或继承表所有者权限。DDL 由独立迁移角色执行，普通启动检查迁移版本与校验和。

设备初始化、恢复密钥、配对码与长期设备 token 提供设备登录。用户名密码绑定到原数据账号，或通过注册创建新数据账号；用户名不能自动关联旧数据。

密码使用随机盐和 Argon2id。Web 会话独立于长期设备凭据，默认使用 HttpOnly、Secure、SameSite=Strict Cookie，也支持 Bearer 凭据。Cookie 写请求检查 Origin，登录和注册请求携带 Origin 时也检查来源。退出和改密吊销 Web 会话；设备凭据由设备管理单独吊销。

## 资源 CRUD 与同步修订

资源路径将表名中的下划线转换为连字符。例如 `bills` 对应 `/api/bills`，`stored_value_accounts` 对应 `/api/stored-value-accounts`。

| 操作 | 请求 | 行为 |
| --- | --- | --- |
| 列表 | `GET /api/bills` | 当前账号下的分页、搜索、日期筛选 |
| 详情 | `GET /api/bills/{id}` | 返回快照与服务器版本 |
| 新增 | `POST /api/bills` | 携带完整快照与 `baseVersion=0` |
| 修改 | `PUT /api/bills/{id}` | 基于详情快照和最近确认的版本提交 |
| 删除 | `DELETE /api/bills/{id}` | 基于最近确认的版本写墓碑，保留历史 |

写入信封包含 `mutationId`、`baseVersion`、`dataVersion`、`updatedAtMs` 和 `snapshot`。删除无需快照。服务端为资源写入设置权威设备 ID 和更新时间。

所有写入复用同步修订事务：检查契约和依赖，读取幂等回执，核对 `baseVersion`，合并未知字段，执行余额与业务投影，更新业务行、版本、历史和回执。任一步失败时回滚事务。

相同 `mutationId` 和内容重试返回原回执。相同 ID 被用于不同请求时拒绝写入。版本过期且内容不同返回 HTTP 409；内容已一致返回 duplicate；缺少业务依赖返回 HTTP 422。资源写入产生的修订可由设备通过增量接口读取。

账单接口接收契约定义的完整快照，包括收支类型、金额、发生时间和结算状态。储值影响沿用同步修订规则，`stored_value_effects` 使用 JSON 编码字符串记录各储值账户的整数影响。接口没有单独的简化账单表单协议。

列表按 ID 升序分页，使用 `limit`、`cursor`、`q`、`fromMs`、`toMs`。日期筛选优先使用业务发生时间。基础统计的记录数量按更新时间统计；账单金额按 `occurred_at` 的左闭右开区间汇总，排除退款和墓碑。

## 契约与演进

`openapi/openapi.yaml` 是公共字段与接口的唯一来源，`x-recoow-sync` 定义实体版本、依赖和兼容规则。各端类型和同步注册表由契约生成，数据库迁移分别维护。

未知字段在读写和同步过程中保留。新增字段应更新契约和相应数据库迁移；破坏性变更需要显式版本转换。客户端通过同步能力接口核对实体版本、世代与协议能力，接口路径变更须与客户端适配一起部署。

全实例备份使用 PostgreSQL custom dump 和媒体目录，包含身份与凭据；账号 SQL 导出用于恢复本账号业务数据。恢复旧备份后更换数据集世代，避免客户端复用已见过的版本水位。源库和原媒体在迁移时保留，具体操作见部署文档。
