# 后端打包、部署与完整数据迁移

所有脚本源文件在 Git 管理的 `deploy/` 和 `scripts/` 中，`dist/` 只保存生成物。
运行包包含 Linux amd64、arm64 静态二进制、生成的字段清单、裸机脚本和 Docker 配置。
目标 Linux 不需要 Go、SQLite 命令行或 Docker；需要 Bash、tar、coreutils。
systemd 安装另需 systemd、curl、util-linux（flock）、useradd/getent。不支持 32 位或非 Linux 系统。

## 1. 在开发电脑打包

```bash
bash scripts/package-backend.sh
# 或指定新文件名，已有文件不会被覆盖
bash scripts/package-backend.sh --output dist/recoow-linux-new.tar.gz
```

构建机需 Go（版本满足 backend/go.mod）、Python 3，首次构建需要访问 Go 模块源。
脚本直接从 iOS 模型生成字段清单，不拷贝旧 dist/schema，不修改 iOS UI 或数据库。
默认输出 `dist/recoow-linux-日期时间.tar.gz`，不包含生产数据、`._*` 或 `.DS_Store`。
运行文件附有 `SHA256SUMS`，传输后可校验；校验检测损坏，不代替来源认证或签名。

## 2. 普通 Linux：无需 Docker、无需 root 直接运行

```bash
mkdir recoow && tar -xzf recoow-linux-new.tar.gz -C recoow
cd recoow
bash run.sh --verify
cp recoow.env.example recoow.env  # 迁移包已含 recoow.env，不要覆盖
bash run.sh
```

程序以前台方式运行，默认监听 `0.0.0.0:8080`，数据在运行包的 `data/`，字段在 `schema/`。
迁移包自带 `data/`，同一条命令即可带原数据启动；账号、设备令牌和同步游标保留，无需重新注册。
没有 systemd 可由已有进程管理器托管 `run.sh`。同一数据目录只能有一个写入实例。

配置一行一个 `RECOOW_KEY=value`，不使用 shell 引号、变量替换、`export` 或行尾注释。
环境变量优先；`RECOOW_ENV_FILE` 可指定配置文件。空 `RECOOW_DATA` / `RECOOW_SCHEMA`
分别回退到运行包的 `data/` / `schema/`。如需其他目录或端口：

```bash
RECOOW_LISTEN=127.0.0.1:8090 RECOOW_DATA=/srv/recoow-data bash run.sh
```

正式使用前配置可信 HTTPS 反向代理，把监听改为 `127.0.0.1:8080`；8080 公网直连仅用于诊断。
新部署默认账号上限为 1，修改 `RECOOW_MAX_ACCOUNTS` 可调整（0 表示不限制），升级保留已有配置。
当前 iOS 初始化不发送 bootstrapToken，自用时保持 `RECOOW_BOOTSTRAP_TOKEN` 为空。

### iOS 连接当前服务器

当前地址为 `http://1.95.182.78:8080`。iOS 的 `Info.plist` 已为 `1.95.182.78`
添加定向 HTTP 例外，需要重新构建并安装 App 才能生效；没有全局放开 HTTP。
地址在「设置 → 云同步 → 服务器」填写，不加 `/api` 后缀。

首次连接时点「初始化新账号（成为主设备）」，离线保存恢复密钥后点「保存并完成」，
再打开「启用全量双向同步」并点「立即同步」。已有账号的其他设备通过主设备生成的
配对码加入。若以前绑定了另一台独立服务器，先解除旧绑定再连接当前服务器。

当前 iOS 初始化请求不携带 `bootstrapToken`；服务端若设置了
`RECOOW_BOOTSTRAP_TOKEN`，需先通过 API 创建主设备，再通过配对码加入。
达到 `RECOOW_MAX_ACCOUNTS` 限额后也应加入已有账号。

配对码只在服务进程内存中保留 `RECOOW_PAIRING_TTL` 秒，重启后端即全部失效，
服务器上没有可查阅或重置的配对码记录。丢失配对码时，在 App 的「云同步」
用初始化时保存的恢复密钥「找回账号」（走 `/api/account/recover`，不占账号配额）；
恢复密钥也丢失时，只能由服务器管理员按上文清空该账号后重新初始化。
初始化完成后密钥可一键复制，密钥只显示一次，务必存到 App 之外。

HTTP 会明文传输设备令牌和同步内容。配置可信 HTTPS 后更新 App 内的服务器地址，
并移除 `Info.plist` 中的该 IP 例外。

### 账号与配额故障处理（`403 account quota exceeded`）

`/api/account/init` 返回 403 `account quota exceeded` 表示该实例账号数已达
`RECOOW_MAX_ACCOUNTS`，不是程序错误。先确认服务本身正常：

```bash
curl -s -i http://<服务器>:8080/api/schema                            # 401 = 服务正常
curl -s -i -X POST http://<服务器>:8080/api/account/init -d '{}'      # 403 = 配额满
curl -s -i -X POST http://<服务器>:8080/api/account/recover \
  -H 'Content-Type: application/json' -d '{"recoveryKey":"probe","deviceName":"probe"}'
# 401 invalid recovery key = 找回接口可用，且该路径不受配额限制
```

出口优先级：**配对码加入 → 恢复密钥找回 → 提额 → 重置账号**。

配对码不存在"查阅/重置"这种操作，它只在服务进程内存里保存 `RECOOW_PAIRING_TTL` 秒，
重启后端即全部失效。恢复密钥明文不落库（服务端只存 SHA-256），是 base64url 编码、
区分大小写、`-` 和 `_` 都是有效字符 —— 只能整串粘贴，不要手抄或改大小写。

**重置账号（会清空该实例全部云端数据，先确认手机本地数据完整）**：
后端启动时若库文件不存在会自动重建，因此不需要 `sqlite3`。
下列路径按 systemd 安装（`/var/lib/recoow`）；用 `run.sh` 直接跑的部署把路径换成包内 `data/`：

```bash
sudo systemctl stop recoow                      # 必须停写，WAL 下热挪库会损坏
sudo ls -l /var/lib/recoow                      # recoow.db + 可能的 -wal/-shm + media/
sudo mv /var/lib/recoow/recoow.db /root/recoow.db.bak-$(date +%F-%H%M)
sudo rm -f /var/lib/recoow/recoow.db-wal /var/lib/recoow/recoow.db-shm
sudo rm -rf /var/lib/recoow/media/*
sudo systemctl start recoow
curl -i -X POST http://127.0.0.1:8080/api/account/init -d '{}'   # 期望 201
```

**必须连 `account` 行一起清掉**：只清业务数据会保留账号行，配额仍满、初始化照样 403。
挪走整个 `.db` 一步到位，且无需改目录属主（`os.MkdirAll` 会补 `media/`）。

无法移走库文件时，也可只清数据（账号表名是单数 `account`，清单见
`backend/internal/store/dump.go` 的 `ddlStatements`）：

```bash
sudo sqlite3 /var/lib/recoow/recoow.db \
  "delete from mutation_receipts; delete from sync_metadata; delete from change_log;
   delete from row_state; delete from media_objects; delete from devices; delete from account;
   vacuum;"
```

没有 `sqlite3` 时可用 `python3 -c` 调 `sqlite3` 模块执行同样的语句。
Docker 部署路径不同：数据在命名卷，用 `docker compose exec recoow ls -l /data` 查看，
且**不要执行 `docker compose down -v`**（会删卷）。

回滚：停服务 → 把 `recoow.db.bak-*` 挪回原位并清掉残留 `-wal`/`-shm` → 启动。
确认 App 能初始化且数据已从手机推回后再删备份。

`RECOOW_MAX_ACCOUNTS=0`（不限）公网裸奔会让任何人建账号占磁盘，应同时设置
`RECOOW_BOOTSTRAP_TOKEN`，并把 8080 收敛到 `127.0.0.1` 走 HTTPS 反代。

## 3. 普通 Linux：安装为 systemd 服务

```bash
cd recoow
sudo bash install.sh
sudo systemctl status recoow --no-pager
sudo journalctl -u recoow -f
curl -i http://127.0.0.1:8080/api/schema
```

未携带令牌返回 **401** 表示服务已就绪。脚本等到接口真实返回 401 才报告成功，
不会把 `systemctl start` 成功等同于后端启动成功。

- 程序：`/opt/recoow`，包含两个架构二进制与后续导出工具。
- 数据：`/var/lib/recoow`，含 SQLite、WAL/SHM、媒体、旧迁移备份。
- 配置：`/etc/recoow/recoow.env`，由 root/recoow 组读取。
- 服务以非 root 的 recoow 用户运行，只开放数据目录写权限。
- 普通运行包升级保留既有配置和数据；迁移包仅恢复到**空数据目录**，绝不覆盖已有数据。
- 旧配置使用其他数据/字段目录时拒绝自动安装，请用 `run.sh` 或人工维护。
- 安装失败请看日志，不自动回退旧数据库；结构迁移可能已发生，需结合原版本与整库备份恢复。

配置改完执行 `sudo systemctl restart recoow`。服务自身 TLS 的证书若不包含回环地址，
安装时用 `RECOOW_HEALTH_URL=https://可信域名/api/schema` 指定可验证地址，不跳过证书验证。

开发电脑一条命令上传并安装：

```bash
bash scripts/deploy-backend.sh --host root@服务器 --archive dist/recoow-linux-new.tar.gz
# 可选：--port 2222 --identity ~/.ssh/id_ed25519
```

使用已有 SSH 密钥、known_hosts 或 SSH Host 别名，不关闭指纹校验，不把密码写进脚本。
远程需 root 或免密 sudo；失败返回非零。命令会短暂停止旧后端，安装后等待接口就绪。

## 4. Docker 部署

在解压后的完整运行包目录执行，构建上下文需要包含双架构二进制和 `schema/`。
Docker 镜像根据目标架构选择程序，以 uid/gid 10001 的非 root 用户运行。
基础镜像默认为 Alpine 3.24，构建时安装 CA 证书和时区数据，依赖下载失败会终止构建。
离线机器需提前准备基础镜像及软件仓库。

```bash
cd recoow
cp deploy.env.example .env
docker compose config
docker compose up -d --build --wait --wait-timeout 120
docker compose ps
```

需要支持 `--wait` 的 Compose；旧版请升级，不以容器已创建判断就绪。
配置修改需 `docker compose up -d --build --wait` 重建，`restart` 不应用新环境变量。
命名卷用于正常部署，旧卷权限不自动修复；bind mount 数据目录需 uid/gid 10001 可写。
不要执行 `docker compose down -v`，会删除数据卷。默认命名卷**不会自动导入迁移包的 ./data**。
Docker 导出的包优先按第 2/3 节恢复到普通 Linux。如需恢复到 Docker，需单独安排停机、空卷
恢复和 10001:10001 属主校验，不要直接 compose up 后误以为数据已加载。

## 5. 在原服务器导出完整可运行迁移包

systemd：

```bash
sudo bash /opt/recoow/export.sh --output /root/recoow-migration.tar.gz
```

Docker（目录必须是当前容器构建使用的**同一版本运行包**）：

```bash
cd /path/to/recoow-package
sudo bash export.sh --mode docker --bundle "$PWD" --container recoow \
  --output /root/recoow-migration.tar.gz
```

已经人工停机、使用其他进程管理器的部署：

```bash
# 先停止所有写入；脚本不会替其他进程管理器停写
bash export.sh --mode stopped --bundle "$PWD" --data "$PWD/data" \
  --output /path/outside-data/recoow-migration.tar.gz
```

导出整个实例：所有账号、设备凭据哈希、同步状态、幂等回执、SQLite 及可能残留的 WAL/SHM、
全部媒体、字段清单、两个架构程序、安装脚本。**不只是单账号 SQL 导出**。旧数据迁移备份保留。
大媒体目录复制会延长停机，需安排维护窗口和足够临时磁盘空间。
systemd/docker 原来运行才重新启动，原来停止则保持停止；并发安装/导出通过 flock 互斥。
无法停止、容器被 SIGKILL 或任一步失败时拒绝交付成功包；包权限为 0600，不覆盖同名文件。
无法阻止人工绕过锁启动服务或其他进程写数据，维护期间不要并行操作实例。

迁移配置保留注册限制、TTL、CORS，重置数据/字段与 TLS 路径，**不打包旧服务器私钥**。
新服务器重新配置域名、HTTPS、防火墙和监听地址。保留账号、设备凭据、数据库版本与同步世代。
切换前停掉旧写入实例，避免两端继续写入造成分叉。新服务器地址仍需在客户端配置。

## 6. 从开发电脑直接拉取迁移包

```bash
bash scripts/export-from-server.sh --host root@服务器 \
  --output dist/recoow-migration.tar.gz
```

Docker 增加 `--mode docker --remote-script /path/to/package/export.sh --bundle /path/to/package`。
另有 `--container`、`--port`、`--identity`；远端路径不支持空格。
通过 SSH 加密流回本机，远端临时目录自动清理，不留下永久压缩包。输出已存在则拒绝。
旧服务器没有新工具时，先用新普通运行包升级部署，再使用导出命令。

## 7. 在新服务器恢复并验证

```bash
mkdir recoow-restored
tar -xzf recoow-migration.tar.gz -C recoow-restored
cd recoow-restored
bash run.sh --verify
sudo bash install.sh
# 或无需 root：bash run.sh
```

包包含全部账号及设备凭据，请按敏感备份保管。首次启动正常执行数据库迁移，因此先校验再运行。
运行后 SQLite 会变化，旧 SHA256SUMS 数据校验不再成立是预期行为；export.sh 只校验不可变运行
文件，再给新快照生成完整校验和，因此仍可再次导出。
验收不仅看 401，还需客户端确认旧账号记录和媒体可见、能同步。验收完成前留存原包与旧服务器备份。
