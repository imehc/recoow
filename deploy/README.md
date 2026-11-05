# PostgreSQL 后端部署与数据迁移

运行包包含 Linux amd64/arm64 API 和 worker 二进制、OpenAPI YAML、裸机脚本与 Docker 配置。应用连接独立 PostgreSQL 服务，`data/` 保存媒体字节。

## 创建数据库与角色

使用 PostgreSQL 15 及以上。迁移角色拥有表，普通运行角色只获得数据访问权限：

```sql
CREATE ROLE recoow_migrator LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '<migration-password>';
CREATE ROLE recoow_app LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '<runtime-password>';
CREATE DATABASE recoow OWNER recoow_migrator;
```

运行账号不能拥有表、加入表所有者角色或绕过 RLS。不要向浏览器或移动端下发数据库连接串。生产连接串根据数据库配置使用 TLS，应用接口使用 HTTPS。

## 构建与初始化

构建机器需要 Go、Python 3、Make 和 OpenAPI Generator 7.22.0。打包脚本自动生成 Go 接口类型，再构建两个架构的静态程序；运行服务器只需要运行包和 PostgreSQL 连接。

```bash
bash scripts/package-backend.sh --output dist/recoow-linux-new.tar.gz
mkdir runtime
tar -xzf dist/recoow-linux-new.tar.gz -C runtime
cd runtime
bash run.sh --verify
cp recoow.env.example recoow.env
```

先使用独立迁移连接执行初始化，并显式指定运行包中的契约路径：

```bash
# 执行文件按实际机器架构选择 amd64 或 arm64
env RECOOW_DATABASE_URL='<runtime-database-url>' \
  RECOOW_MIGRATION_DATABASE_URL='<migration-database-url>' \
  RECOOW_OPENAPI="$PWD/openapi/openapi.yaml" \
  ./recoow-api-linux-amd64 -migrate
```

二进制直接读取环境变量，`run.sh` 负责加载配置文件。在 `recoow.env` 填写运行连接和媒体目录；日常运行保持 `RECOOW_MIGRATION_DATABASE_URL` 为空。普通启动只检查迁移版本和校验和。

```bash
bash run.sh
# systemd 安装：sudo bash install.sh
```

配置一行一个 `KEY=value`，不使用 shell 引号、变量替换或 `export`；环境变量优先。`RECOOW_ENV_FILE` 可指定配置文件。空 `RECOOW_DATA` / `RECOOW_OPENAPI` 分别使用运行包 `data/` / `openapi/openapi.yaml`。配置项说明见 `backend/README.md`。

API 自带定时会话清理；独立 `recoow-worker-linux-<架构>` 可作为单独维护进程运行，也需要相同数据库、OpenAPI 与媒体配置。它不会清除业务墓碑、历史或媒体引用。

部署模板注册账号配额为 1，0 表示不限。若设置引导密钥，注册请求必须携带；客户端初始化不发送该字段时，先通过 API 创建账号再配对。Web Cookie 默认要求 HTTPS；本地 HTTP 开发使用 `RECOOW_ALLOW_INSECURE_COOKIES=1`。

## Docker

`docker-compose.yml` 连接已有 PostgreSQL，运行前在 Compose 环境配置 `RECOOW_DATABASE_URL`。数据库迁移使用独立的一次性命令，不能把迁移凭据长期留给应用容器。

```bash
docker build -t recoow-backend:latest .
# 迁移连接仅提供给一次性容器
docker run --rm --env-file recoow.env \
  -e RECOOW_OPENAPI=/app/openapi/openapi.yaml \
  -e RECOOW_MIGRATION_DATABASE_URL='<migration-database-url>' \
  recoow-backend:latest -migrate
docker compose up -d
```

容器数据卷保存媒体。健康检查调用不带凭据的 `/api/sync/capabilities`，预期 401。运行服务与数据库应位于适当的受控网络。

## SQLite → PostgreSQL

该步骤只迁移后端库，移动端仍使用自身本地数据库。当前工具支持已经升级完成的多账号全量同步 SQLite 库；更旧的库先用最后一个 SQLite 后端版本完成升级。

1. 使用旧版本的导出脚本或停服复制，取得包含 `recoow.db` 和 `media/` 的完整副本；保留原服务与原目录用于回滚。
2. 创建独立目标 PostgreSQL 库，执行当前 `-migrate`。迁移工具拒绝任何非空目标数据表。
3. 在开发或受控迁移机器安装 Python 依赖，设置指向目标库的 `RECOOW_MIGRATION_DATABASE_URL`，执行：

```bash
.venv/bin/python -m pip install -r scripts/requirements.txt
.venv/bin/python scripts/migrate_backend_sqlite.py \
  --source /path/to/source-copy/recoow.db \
  --media-source /path/to/source-copy/media \
  --media-target /path/to/new-data/media
```

媒体目标目录必须不存在。工具以只读方式打开源库，在事务中迁移账号、设备、业务状态、变更历史、幂等回执、世代及媒体索引，并核对行数、快照、版本和媒体 SHA-256。未知实体、缺失依赖或损坏媒体导致迁移失败，校验阶段写入会回滚。

数据库与文件系统没有共同事务。工具在提交前安置已校验媒体；若提交时连接异常，会保留目标媒体，须检查目标数据库是否已提交后再决定重试。源文件始终保留。

4. 在目标服务验证原恢复密钥、设备 token、全量快照、增量同步、媒体、余额与统计，再切换客户端地址。
5. 真正切换并确认恢复路径后再按数据保留策略退役旧实例。工具不自动替换运行服务或删除源库。

## 整实例备份与恢复

导出脚本使用 `pg_dump --format=custom`，同时复制媒体、程序与 OpenAPI；运行中的 API 会短暂停止并恢复。备份账号必须能读取全部账号数据，普通 RLS 运行账号不能代替它。执行机器需要与数据库版本兼容的 PostgreSQL 客户端工具。

```bash
# 在环境中配置能够读取完整数据库的 RECOOW_BACKUP_DATABASE_URL
bash export.sh --mode stopped --data /path/to/data --output /path/to/backup.tar.gz
# systemd：sudo -E bash export.sh --mode systemd --output /path/to/backup.tar.gz
# Docker：sudo -E bash export.sh --mode docker --container recoow --output /path/to/backup.tar.gz
```

导出包含 `database.dump`、`data/media/`、二进制、配置模板及校验清单。配置中的数据库连接串和 TLS 路径被清空，恢复后重新配置。归档不包含旧 SQLite 数据文件；源目录不被修改。

恢复到新的空 PostgreSQL 库和新的媒体目录，先校验并恢复数据库：

```bash
bash run.sh --verify
pg_restore --exit-on-error --single-transaction --no-owner --no-privileges \
  --dbname '<new-migration-database-url>' database.dump
```

随后使用 `-migrate` 验证校验和并为新运行角色授权，配置 `RECOOW_DATA` 指向恢复的媒体目录。已有目标库与数据目录不自动覆盖。全库备份包含密码身份与会话；`GET /api/exports/sql` 是本账号数据导出，不能替代整实例备份。

恢复较旧数据集后更换各账号 `sync_metadata.generation`，避免复用客户端已见过的版本水位；原样搬迁且所有数据一致时可以保留世代。
