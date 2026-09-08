# recoow 云同步后端（backend）

个人多设备同步的 Go 后端，提供账号配对、全量快照、增量同步、修订冲突回执和媒体传输。
账号、设备、行快照、变更日志、回执和媒体索引使用 **SQLite**（纯 Go 驱动，无 CGO）；
媒体字节单独按内容寻址存储。服务按单账号、多设备部署。

## 生成、测试与构建

需要 Python 3 和 `go.mod` 指定的 Go 版本；iOS 构建还需要 macOS 与 Xcode。
以下命令在仓库根目录执行：

```bash
make generate         # 从 iOS 模型生成 JSON 契约、Swift 注册表和 Xcode 输入清单
make check-generated  # 校验生成物和全部实体覆盖
make test             # 脚本回归、后端测试与 go vet
make backend          # 输出 dist/recoow-api 和 dist/schema/
make ios-build        # 只构建 iOS 模拟器版本，不安装或运行 App
```

JSON 契约和 Swift 注册表是生成产物，不纳入 Git；首次克隆后先执行 `make generate`。
Xcode 编译时也会生成 Swift 注册表，新增模型后需重新运行 `make generate` 更新构建输入清单。

## 目录结构（分层）

```
backend/
  cmd/api/              入口（读配置、加载 schema、起 HTTP、优雅退出）
  internal/
    api/                HTTP handler、路由、中间件、DTO
    auth/               设备配对码 + 载体令牌（无密码）
    schema/             读取并校验 recoow/schema 字段清单
    store/              仓储接口、修订检查、持久化回执、聚合投影、SQL 导出
    config/             环境变量配置
```

## 存储与备份

- 数据库文件：`<RECOOW_DATA>/recoow.db`（SQLite，WAL 模式，随启动自动建表）。
- 媒体字节：`<RECOOW_DATA>/media/<account>/<checksum>`，按内容寻址、可被多个 key 共享。
- 升级存储结构前自动备份已有数据库到 `<RECOOW_DATA>/backups/`；备份失败则停止升级。
- SQL 导出包含修订日志与回执，**不包含媒体文件**；完整备份还须保存 `media/` 目录。
- 不要直接删除墓碑、变更历史或仍被引用的媒体；它们用于离线设备恢复和冲突处理。
- 恢复时停止服务，使用新目录，保留原数据目录以便回滚。SQL 回放目标为 SQLite：

```bash
# 导出（设备 token 鉴权）
curl -s -o recoow-backup.sql "localhost:8080/api/export/sql" -H "authorization: Bearer $TOKEN"

# 导入到全新目录，并另外恢复 media/ 到该目录
mkdir -p restored-data
sqlite3 restored-data/recoow.db < recoow-backup.sql

# 回退旧快照或替换数据集时更换世代，避免复用设备已经见过的版本号
sqlite3 restored-data/recoow.db \
  "UPDATE sync_metadata SET generation = lower(hex(randomblob(16))), min_cursor = 0 WHERE id = 1;"
```

## 运行

```bash
# 仓库根目录
make generate
cd backend
export RECOOW_BOOTSTRAP_TOKEN='设置一个随机引导密钥'
go run ./cmd/api
```

运行打包产物时，在仓库根目录显式指定契约路径：

```bash
RECOOW_DATA=./data RECOOW_SCHEMA=./dist/schema \
RECOOW_BOOTSTRAP_TOKEN='设置一个随机引导密钥' ./dist/recoow-api
```

生产部署请使用 HTTPS 或配置可信的 TLS 反向代理，不要通过公网明文传输设备 token。

配置（环境变量）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `RECOOW_LISTEN` | `:8080` | 监听地址 |
| `RECOOW_DATA` | `./data` | 数据目录：SQLite 库 `recoow.db` 与媒体 `media/` 落盘于此 |
| `RECOOW_SCHEMA` | `../schema` | 字段清单目录 |
| `RECOOW_BOOTSTRAP_TOKEN` | 空 | 若设置，初始化账号必须携带 |
| `RECOOW_PAIRING_TTL` | `300` | 配对码有效期（秒） |
| `RECOOW_CORS_ORIGINS` | 空 | 允许跨源访问 /api 的来源，逗号分隔；生产环境使用明确的来源地址 |
| `RECOOW_TLS_CERT` / `RECOOW_TLS_KEY` | 空 | 提供即启用 HTTPS |

## iOS 使用

在设置的“云同步”中填写服务器地址并初始化或配对账号，然后启用“全量双向同步”并执行“立即同步”。
开关按服务器与账号保存，默认关闭；升级不会自动开启同步。首次连接新数据集前自动保存本地完整备份，
备份失败不会继续同步。仍建议升级前主动导出一份备份到 App 之外。

同步覆盖全部已注册模块及媒体，不按单项功能开关。冲突保留本地内容，进入“处理同步冲突”选择保留本地或使用云端。
更换服务器或账号时重新初始化进度，不沿用其他数据集的游标。

## 接入管理客户端

Web 等客户端可读取 `/api/schema` 获取契约，通过相同的同步接口写入，并使用配对获得的设备 token。
跨源访问时设置 `RECOOW_CORS_ORIGINS`，例如 `http://localhost:8000`。不要绕过同步接口直接写 SQLite。
独立管理界面、分页检索、角色权限等不由通用同步接口自动生成。

## 核心 API

| 方法与路径 | 鉴权 | 说明 |
| --- | --- | --- |
| `POST /api/account/init` | 引导 token | 初始化单账号，返回恢复密钥（仅一次）与主设备 token |
| `POST /api/devices/pairing-code` | 主设备 | 生成一次性配对码 |
| `POST /api/auth/pair` | 无 | 用配对码注册新设备，换取设备 token |
| `GET/DELETE /api/devices...` | 设备 token | 设备列表 / 吊销 |
| `GET /api/schema` | 设备 token | 下发字段清单（含版本号），前端据此动态渲染 |
| `GET /api/sync/capabilities` | 设备 token | 协议版本、契约版本、数据集世代和快照水位 |
| `GET /api/sync/bootstrap?generation=&watermark=&after=&limit=` | 设备 token | 固定水位的全量分页快照，包含墓碑 |
| `POST /api/sync/push` | 设备 token | 批量修订检查与幂等回执；冲突返回当前快照 |
| `GET /api/sync/pull?generation=&cursor=&limit=` | 设备 token | 按游标增量拉取 |
| `GET /api/rows/{table}` | 设备 token | 某张表的存活行（后台浏览） |
| `GET /api/export` | 设备 token | 全量结构化导出 |
| `GET /api/export/sql` | 设备 token | 整库 SQL 导出（DDL+INSERT，可回放重建/迁库） |
| `PUT/GET/HEAD/DELETE /api/media/objects/{key}` | 设备 token | 媒体上传/下载/删除（按 checksum 去重） |

## 一键引导（curl 示例）

```bash
# 1) 初始化账号（主设备）
curl -s -X POST localhost:8080/api/account/init \
  -H 'content-type: application/json' \
  -d '{"bootstrapToken":"<你的引导token>"}'
# 返回 recoveryKey（请离线保存）与 deviceToken（记为 $OWNER）

# 2) 主设备生成配对码
curl -s -X POST localhost:8080/api/devices/pairing-code \
  -H "authorization: Bearer $OWNER"

# 3) 新设备用配对码加入，得到该设备自己的 token
curl -s -X POST localhost:8080/api/auth/pair \
  -H 'content-type: application/json' \
  -d '{"code":"<配对码>","deviceName":"iPad"}'

# 4) 获取世代和契约，将 dataset.generation 记为 $GENERATION
curl -s localhost:8080/api/sync/capabilities -H "authorization: Bearer $TOKEN"
curl -s localhost:8080/api/schema -H "authorization: Bearer $TOKEN"

# 5) 新建日记标签；真实客户端应生成唯一实体 ID 和 mutationId
curl -s -X POST localhost:8080/api/sync/push -H "authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  --data @- <<JSON
{"protocolVersion":2,"generation":"$GENERATION","items":[{"table":"diary_tags","id":"tag-demo","mutationId":"tag-demo-create","baseVersion":0,"dataVersion":1,"operation":"insert","updatedAtMs":1700000000000,"deviceId":"web-demo","snapshot":{"id":"tag-demo","created_at":1700000000000,"updated_at":1700000000000,"deleted_at":null,"device_id":"web-demo","name":"旅行","note":null}}]}
JSON

# 6) pull 增量（新设备先完成 bootstrap，再从其 watermark 开始）
curl -s "localhost:8080/api/sync/pull?generation=$GENERATION&cursor=0" -H "authorization: Bearer $TOKEN"
```

更新或删除使用上次确认的 `baseVersion`；重试必须保持同一 `mutationId` 和原请求内容。
`409 reset_required` 表示世代或游标失效，需要重新 bootstrap，不能简单清零后跳过本地存量。
媒体先上传到 `sync/<sha256>`，再在快照的 `_media` 中引用已确认的 key、checksum、size 和 contentType；单文件上限 200 MiB。

## 字段清单（`../schema/*.json`）

- 每个同步实体生成一份 JSON，后端运行时据此配置开放实体、验证和下发契约。
- 行快照字段名沿用 iOS `CodingKeys`（= GRDB 列名），全网 **snake_case**。
- 修改 iOS 模型后运行 `make generate`，并在 `scripts/sync_contract_rules.py` 维护版本、默认值和依赖。
  普通字段或实体新增不需要修改后端物理表；部署时一并更新生成契约。
- 未知普通字段保留；必填、类型、枚举和支持的版本范围严格校验。
  破坏性业务变化仍需明确转换逻辑及客户端升级，不能只提高版本号或自动生成 UI。
- 系统保留列 `id/created_at/updated_at/deleted_at/device_id/server_version` 恒定，
  后台界面隐藏；媒体二进制列标注 `"binary": true` 不进快照。

## 规范约定

- **注释一律中文**，标识符 / 字段名保持英文。
- 开发后端遵循仓库中的 Go 布局、安全、并发和网络规范。
- 合入门禁：`gofmt`、`go vet ./...`、`go test ./...` 全绿。
