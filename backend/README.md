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
| `GET /api/sync/capabilities`、`GET /api/sync/snapshots` | 同步能力、固定水位快照 |
| `POST /api/sync/mutations`、`GET /api/sync/changes` | 修订写入、增量变化 |
| `GET /api/exports`、`GET /api/exports/sql` | 本账号数据、PostgreSQL SQL 导出 |
| `PUT/GET/HEAD/DELETE /api/media/objects/{key}` | 媒体传输 |

实体资源名采用连字符，例如 `diary-tags`、`stored-value-accounts`。鉴权后的账号由服务端解析，请求不能指定数据归属。

版本接口返回 `version` 和 `commit`，使用 `Cache-Control: no-store` 避免部署后读到旧版本。发布版本与同步协议、数据表版本分别维护；客户端判断同步兼容性仍使用已鉴权的 `/api/sync/capabilities`。

列表使用 `limit=1..200`、`cursor`、`q`、`fromMs`、`toMs`，按 ID 升序分页。日期筛选优先使用实体发生时间，缺少该字段时使用服务器更新时间。统计中的记录数量按更新时间统计；账单金额按 `occurred_at` 的左闭右开区间统计，退款和墓碑排除，分别返回支出、收入和储值消费。当前提供基础统计，复杂趋势和维度聚合可在后续增加。

写入提供 `mutationId`、`baseVersion`、`dataVersion`、`updatedAtMs` 与 `snapshot`；删除无需快照。更新使用详情接口读取到的完整快照，并携带最近确认的版本；过期版本且内容不同返回 409，内容一致返回 duplicate，缺依赖返回 422。重试保持原 mutationId 和内容，Web 写入也会进入移动端同步日志。

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
