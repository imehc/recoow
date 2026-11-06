# recoow 后端

Go 服务供 iOS、Android 和 Web 共用。业务数据存储于 PostgreSQL，驱动为 pgx v5，固定查询由 sqlc 生成。媒体按内容寻址存放在 `RECOOW_DATA/media/<账号>/<checksum>`。

## 数据与分层

- 21 个同步实体各自对应业务表，字段由 OpenAPI 契约定义；金额使用整数分，业务时间使用毫秒整数，ID 使用字符串。
- 业务表的 `tenant_id` 表示数据归属；储值调整中的 `account_id` 仍表示业务上的储值账户。
- `extras JSONB` 只保存未知字段及扩展内容，`present_fields` 区分字段缺省与显式空值；`row_state` 是同步兼容视图，当前业务状态不再保存到通用 JSON 快照表。
- `change_log` 保存版本化历史快照，`mutation_receipts` 保存幂等回执，`sync_metadata` 保存世代与游标水位。管理端写入与同步共用同一修订事务和业务规则。
- 业务表、历史、回执、媒体索引及同步元数据开启强制 RLS；账号上下文只在事务内有效。运行角色不能是超级用户、BYPASSRLS、表所有者或表所有者角色成员。

```text
backend/
  cmd/api/           HTTP 服务入口
  cmd/worker/        后台维护入口
  internal/app/      依赖装配
  internal/config/   环境变量
  internal/domain/   实体与仓储接口
  internal/handler/  HTTP 路由、鉴权与 DTO
  internal/service/  身份、修订校验、冲突与业务投影
  internal/repository/  PostgreSQL、固定 SQL 与 sqlc 生成代码
  internal/schema/   从 OpenAPI 派生同步校验元数据
  migrations/        带校验和的 PostgreSQL 迁移
```

普通运行进程只检查迁移版本。使用独立迁移角色执行 `-migrate`；数据库版本缺失或校验和改变时拒绝启动。API 进程定时清理过期 Web 会话；独立 worker 可用于后续维护任务。

## 统一契约与生成

唯一契约为 [`openapi/openapi.yaml`](../openapi/openapi.yaml)，遵循资源式路径。`x-recoow-sync` 定义同步版本、依赖和兼容规则。存储与修订设计见 [后端架构](../docs/backend-architecture.md)。

根 Makefile 固定 OpenAPI Generator 7.22.0 和 sqlc v1.30.0，提供：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements.txt
make generate-go-api generate-sql
make generate-ios-api generate-android-api generate-web-api
make generate                  # 全部接口类型、sqlc 与 iOS 同步注册表
```

生成的 HTTP 类型位于各端构建目录，不手动修改。sqlc 查询源文件在 `internal/repository/queries/`，生成的查询代码纳入版本管理；动态同步 SQL 的标识符来自契约，数据值始终通过参数传入。

修改公共字段先修改 OpenAPI，随后更新 PostgreSQL 迁移和移动端迁移。未知字段必须保留；破坏性升级需要显式版本转换，不能仅依赖类型生成。

## 登录与 API

设备初始化、恢复密钥、配对码和长期设备 token 保留。密码身份绑定到同一个数据账号；已有账号由主设备绑定用户名密码，新用户可单独注册。用户名归一化为小写；密码采用带随机盐的 Argon2id（64 MiB、3 次、并行度 2），长度为 12–256 字节。

Web 登录使用独立的可过期会话，保存 token 哈希；默认 Cookie 为 HttpOnly、Secure、SameSite=Strict。Cookie 写请求检查 Origin。登录响应也返回 Bearer 会话凭据，退出和修改密码会吊销会话；长期设备凭据继续由设备管理单独吊销。账号禁用对会话与设备登录均生效。登录失败按直连来源地址限流。

登录响应的 `access` 表示服务端授权的会话范围。首版 Web 登录及升级前已有会话均为 `read_only`，允许查询、统计、历史、媒体读取和退出；业务写入、同步上传、媒体修改、设备管理、改密及账号导出被拒绝，Cookie 和 Bearer 采用相同限制。长期设备凭据保留原有权限，客户端类型 Header 不能改变授权范围。

| API | 用途 |
| --- | --- |
| `GET /api/version` | 无需登录，查询运行中的后端版本和构建提交 |
| `GET /api/openapi.yaml` | 读取完整公共契约 |
| `POST /api/accounts`、`POST /api/account-recoveries` | 设备初始化、恢复账号 |
| `POST /api/device-pairing-codes`、`POST /api/device-pairings` | 创建、原子消费配对码 |
| `GET /api/devices`、`DELETE /api/devices/{id}` | 管理本账号设备 |
| `POST /api/users`、`POST /api/users/me/credentials` | 密码账号注册、原账号绑定 |
| `POST /api/sessions`、`DELETE /api/sessions/current` | 登录、退出 |
| `GET /api/users/me`、`PUT /api/users/me/password` | 当前身份、修改密码 |
| `GET/POST /api/{实体}`、`GET/PUT/DELETE /api/{实体}/{id}` | 本账号分页列表、详情与修订写入 |
| `GET /api/statistics` | 本账号基础统计 |
| `GET /api/change-events`、`GET /api/change-events/{eventId}` | 云端变更摘要、时间来源、历史快照与字段差异 |
| `GET /api/sync/capabilities`、`GET /api/sync/snapshots` | 同步能力、固定水位快照 |
| `GET /api/sync/manifests`、`POST /api/sync/snapshot-queries` | 首次绑定或备份恢复时比较数据目录，只读取缺失或不同的快照 |
| `POST /api/sync/mutations`、`GET /api/sync/changes` | 修订写入、增量变化 |
| `DELETE /api/sync/dataset?generation=<当前世代>` | 仅主设备清空本账号云端备份，返回新的世代 |
| `GET /api/exports`、`GET /api/exports/sql` | 本账号数据、PostgreSQL SQL 导出 |
| `PUT/GET/HEAD/DELETE /api/media/objects/{key}` | 媒体传输 |

实体资源名采用连字符，例如 `diary-tags`、`stored-value-accounts`。鉴权后的账号由服务端解析，请求不能指定数据归属。

版本接口返回 `version` 和 `commit`，使用 `Cache-Control: no-store` 避免部署后读到旧版本。发布版本与同步协议、数据表版本分别维护；客户端判断同步兼容性仍使用已鉴权的 `/api/sync/capabilities`。

`clear_dataset` 能力表示支持清空云端备份：删除当前账号的业务记录、媒体文件、变更历史和幂等回执，保留账号、恢复密钥与设备绑定；其他账号不受影响。操作必须携带界面确认时的 `generation`，世代已变化则返回 409。清空前关闭其他设备同步，避免旧客户端重新上传；iOS 执行后暂停同步，再次启用会重新上传本机数据。此操作不清理服务器管理员保存的离线备份。

素材冲突比较使用内容校验和与媒体引用，存储路径不作为业务差异；JSON 数字按精确十进制值比较，`0`、`0.0` 与等值科学计数法不会产生假冲突，大整数保留精度。

首次同步在服务端声明 `manifest_bootstrap_v1` 时先获取同一云端版本的数据目录，比较 ID、删除状态和内容摘要，再按目录中的版本批量读取必要快照。仍需比较完整目录，相同记录省去的是完整内容传输。已验证相同且尚未发送的本地盘点记录建立同步基线后跳过上传；已有冻结请求继续按原 mutation 重试。同 ID 内容不同和删除差异仍使用现有冲突规则。目录分批落地后接续原有增量变化，旧服务端回退到完整快照。媒体上传前使用 HEAD 核对云端声明的 checksum 和大小，下载继续复用本地已校验的内容。此优化不恢复备份中的鉴权凭据，也不直接信任备份游标。

列表使用 `limit=1..200`、`cursor`、`q`、`fromMs`、`toMs`，按 ID 升序分页。日期筛选优先使用实体发生时间，缺少该字段时使用服务器更新时间。统计中的记录数量按更新时间统计；账单金额按 `occurred_at` 的左闭右开区间统计，退款和墓碑排除，分别返回支出、收入和储值消费。当前提供基础统计，复杂趋势和维度聚合可在后续增加。

写入提供 `mutationId`、`baseVersion`、`dataVersion`、`updatedAtMs` 与 `snapshot`；删除无需快照。更新使用详情接口读取到的完整快照，并携带最近确认的版本；版本过期时，以历史基础快照、本次快照和当前快照进行三方比较：独立字段修改自动合并；相同修改返回 duplicate；争议字段、账单结算组及删除冲突返回 409，缺依赖返回 422。人工确认仍携带原 baseVersion，并同时提供 `resolution=local|remote` 与界面展示的 `expectedVersion`；只选择争议内容，保留兼容修改，版本已变化则重新返回冲突。重试保持原 mutationId 和内容，Web 写入也会进入移动端同步日志。

`change_history_v1` 表示支持独立云端历史查询及回执 `events` 关联。事件保存客户端修改时间和服务端接受时间，按修订顺序排列；失败、无变化的 duplicate 和相同 mutation 重试不创建新事件。新增、修改、删除、墓碑恢复、自动合并和人工确认分别标识，派生修订标为系统计算并关联触发操作。列表支持实体、记录、原始平台、鉴权提交设备及服务端时间筛选，分页游标绑定账号、世代、查询版本与筛选条件；详情读取当时的前后快照，字段差异保留未知字段、null 与整数精度。

`mutation_origin_v1` 允许同步 mutation 附带冻结的 `origin`。`X-Recoow-Client`、`X-Recoow-App-Version`、`X-Recoow-OS`、`X-Recoow-OS-Version` 只描述此次提交环境，不能反推离线编辑的原始来源；新客户端发送 `origin` 前先核对能力。鉴权提交设备与会话由服务端解析，来源资料按事件保存。旧历史没有服务端时间和鉴权来源时为空，旧客户端没有上报平台时保留 unknown。

## 配置与运行

要求 Go 满足 `go.mod`，PostgreSQL 15 及以上。配置与迁移步骤见 [部署文档](../deploy/README.md)。

| 配置 | 用途 |
| --- | --- |
| `RECOOW_DATABASE_URL` | 必填，普通运行角色 PostgreSQL 连接串 |
| `RECOOW_MIGRATION_DATABASE_URL` | 独立迁移角色，仅迁移时提供 |
| `RECOOW_OPENAPI` | 默认 `../openapi/openapi.yaml` |
| `RECOOW_DATA` | 默认 `./data`，保存媒体字节 |
| `RECOOW_LISTEN` | 默认 `:8080` |
| `RECOOW_SESSION_TTL` | 会话秒数，默认 86400 |
| `RECOOW_ALLOW_INSECURE_COOKIES` | 本地 HTTP 开发设为 1，部署默认 0 |
| `RECOOW_BOOTSTRAP_TOKEN`、`RECOOW_MAX_ACCOUNTS` | 注册入口限制、账号配额；程序默认不限制，部署模板为 1，0 表示不限制 |
| `RECOOW_PAIRING_TTL` | 配对码秒数，默认 300 |
| `RECOOW_CORS_ORIGINS` | 明确的 Web 来源，逗号分隔；Cookie 跨源不使用通配符 |
| `RECOOW_TLS_CERT`、`RECOOW_TLS_KEY` | 服务自身 TLS，或由可信代理提供 HTTPS |

```bash
cd backend
go run ./cmd/api -migrate   # 提前设置迁移与运行连接串
# 正常运行时只保留 RECOOW_DATABASE_URL
go run ./cmd/api
```

整实例备份使用 `pg_dump` 加媒体目录；账号 SQL 导出只供本账号数据恢复，不代替包含密码身份、会话和全部账号的整实例备份。

资源 CRUD 接收完整同步快照，账单储值影响沿用同步修订规则。调用方需按契约构造账单字段；简化表单并非单独的后端接口。
