# recoow Web

基于 Svelte 5、SvelteKit、TypeScript、shadcn-svelte 与 ECharts 的云端工作台。提供登录、21 类实体查询、记录关系图、授权的核心在线编辑、批量整理、账单统计、变更历史、媒体预览和设备查看。

协作遵循 [AGENTS.md](../AGENTS.md)，界面遵循 [DESIGN.md](DESIGN.md)；与移动端共享信息含义和颜色角色，布局、字号与间距使用 Web 自身的变量和组件。

## 本地开发

需要 Node.js 22.12 或更新版本、pnpm，以及仓库公共契约工具：Java 17、Make、OpenAPI Generator（版本见根 Makefile）和安装了 `scripts/requirements.txt` 的 Python。生成脚本优先使用根 `.venv`，也可通过 `PYTHON` 指定解释器。

```sh
cd web
pnpm install
pnpm generate
cp .env.example .env.local
pnpm dev
```

前端不会自动启动 Go API。另开终端，在仓库根目录运行 `bash backend/dev.sh`，需要 Go 和 Docker，具体数据保存位置见 [后端开发说明](../backend/README.md#配置与运行)。开发库需要单独注册或绑定密码账号；线上账号不会自动出现在本机。

`RECOOW_API_URL` 只用于开发与预览代理，支持 `.env.local`，终端环境变量优先；修改后重启 `pnpm dev`。浏览器始终访问同源 `/api`。本机 HTTP 联调时，后端需要 `RECOOW_ALLOW_INSECURE_COOKIES=1`，开发脚本已设置；生产使用 HTTPS 和默认安全 Cookie。

若 `/api/users/me` 返回 **502**，先检查后端是否运行、代理目标是否正确；默认目标为 `http://127.0.0.1:8080`。正常未登录返回 **401**，随后显示登录页。

## 维护命令

```sh
pnpm format
pnpm format:check
pnpm lint
pnpm check
pnpm test
pnpm build
pnpm exec playwright install chrome
pnpm test:browser
pnpm verify
```

格式化同时覆盖 Web 与公共 `openapi/openapi.yaml`，YAML 保持多行结构。实体元数据和 API 类型从公共 OpenAPI 生成，生成结果不纳入 Git。

浏览器检查默认使用已安装的 Chrome，覆盖桌面和移动视口模拟；CI 同样安装并使用 Chrome。需要其他 Chrome 通道时可设置 `RECOOW_BROWSER_CHANNEL`。与 Go 服务的集成检查使用独立测试库，开启测试账号注册后执行：

```sh
RECOOW_TEST_BACKEND=1 RECOOW_API_URL=http://127.0.0.1:8080 pnpm test:browser
```

如测试后端设置了注册口令，还需要 `RECOOW_TEST_BOOTSTRAP_TOKEN`。该检查创建临时账号，不应对生产服务执行。

在线编辑联调运行 `RECOOW_TEST_WEB_WRITES=1 RECOOW_API_URL=<隔离后端地址> pnpm test:browser tests/browser/backend-editing.spec.ts`。测试创建临时账号，用主设备凭据为该账号开启 Web 编辑，并验证同一会话刷新应用授权和立即撤销。只读后端集成用例使用上面的 `RECOOW_TEST_BACKEND`。

## 数据与界面偏好

Web 只显示服务端已收到的数据，不保存本地业务数据库或离线写入队列。查询仅在内存中缓存，退出、会话过期、账号切换和数据集世代变化会清理缓存。页面重新聚焦或恢复联网时重新验证身份与世代，并刷新查询。

浅色、深色、跟随系统，以及简体中文、English、跟随系统可随时切换。`localStorage` 仅保存主题和语言偏好，不保存账号、Token 或业务记录。时间按浏览器时区显示，客户端修改时间与服务端接受时间分别展示；缺失历史信息不推算。整数金额保持精度，界面语言不会改变原始记录内容或货币单位。

## 记录关系

从记录详情的「记录关系」进入，以当前记录为起点查看图或关联列表；选中记录后可继续展开、打开详情或查询历史。图支持拖动、缩放和复位，列表提供相同的键盘操作。

关系使用契约中的记录依赖，以及客户端已保存的日记来源、附件所属记录、抽选来源和饮食账单 ID；不从标签文本、日期、标题或快照内部字段推测关系。快照引用与当前关联分别标识，保存的名称不替代来源记录的当前内容。现有详情接口不区分删除与不存在，两者合并显示；无权限和加载失败分别处理。

反向查询复用只读搜索接口，再核对完整 ID 和类型。每次最多查询 6 次、增加 20 个节点，候选分页按类别轮转；未查完时提供继续加载。每张图最多 60 个节点、从起点展开 3 层，达到上限后可打开另一条记录继续查看。关联数据仅留在页面内存，刷新查询、退出、账号切换和数据集世代变化后重新加载。

## 在线编辑

服务端在当前身份响应中同时声明 `access=read_write` 和允许的 `writable_tables` 时，账单、日记、物品和日记标签页面提供新增与编辑，编辑页提供删除确认。旧后端、缺失权限或只读会话保持查询入口并提供开启指引。授权按账号默认关闭，在 iOS 或 Android 主设备的「设置 → 账号与云同步 → Web 访问」开启或关闭；保存后刷新 Web 即可应用，关闭后服务器立即拒绝写入。接口为 `PUT /api/accounts/current/web-access`，请求 `{"enabled":true}` 或 false；Web 会话不能自行授权。

开启后已有会话刷新页面或点击顶部「刷新云端数据」即可应用，无需退出登录。页面重新聚焦或恢复联网时也会更新权限。关闭后服务端立即拒绝写入，刷新后编辑入口随之隐藏；其他账号不受影响。

表单从完整快照建立编辑基线，只改写实际编辑字段；未知字段、已有媒体、缺省与显式空值、整数金额和毫秒时间精度保留。依赖从已同步记录中搜索选择，日记标签保留移动端的 key/value 结构。编辑中刷新查询不会覆盖输入，离开页面会提示未提交内容；输入仅保留在当前页面内存。

提交结果待确认时锁定内容，重试同一 mutation 和序列化请求；收到冲突后比较基础、本次与最新内容，明确选择后以新 mutation 确认。服务端继续合并兼容修改并再次核对展示版本。确认保存后更新查询缓存，记录、统计和历史下次读取获取新内容。账号、会话或数据集世代变化会清理编辑状态，旧请求携带原世代，服务端拒绝跨世代写入。

## 账单统计与批量整理

「账单统计」按浏览器 IANA 时区展示所选年份的月度收支、上年度金额和分类支出。服务端聚合整数分，排除退款和墓碑；点击金额或分类进入携带发生时间、交易类型和精确分类的账单列表，空分类单独匹配未分类。列表中的账单展示实付金额、类型、分类和发生时间；物品展示位置与关联分类名称，关联暂不可用时保留 ID。

账单、日记和物品列表可选择已加载的记录，每次最多 50 条，分别整理账单分类、日记标签或物品位置。先预览各条记录的前后值，再逐条提交；保留未知字段与未改数据。标签以 key 匹配，移除时不按同名标签猜测。预览冻结基础版本和请求，列表刷新不会改变它们。结果分别汇总已保存、无需更改、无法整理、失败和冲突；冲突打开记录后单独处理。回执未知时暂停后续提交，重试相同请求确认后继续。整理状态只保留在页面内存，离开前提示未提交与未知回执，已保存的记录不会回滚。

## 静态部署

Web 的独立版本从 `0.0.1` 开始，以根目录 `versions.json` 为来源，`package.json` 的版本由公共脚本同步。升级 Web 不改变 Android、iOS 或后端版本；标签使用 `web/v<版本>`。

在仓库根目录运行：

```sh
python3 scripts/version.py show web
python3 scripts/version.py bump web patch
python3 scripts/version.py check
python3 scripts/version.py ci web --tag web/v0.0.1
```

`bump` 仅更新版本文件，不提交、创建标签或发布。正式标签须与当前版本一致，静态站点与后端按兼容契约共同部署。

`pnpm build` 输出 `build/`。将静态文件与 Go API 部署在同一 HTTPS 来源，`/api/` 代理到 Go，未匹配的应用页面路径回退到 `index.html`。默认部署在来源根路径；设置 `RECOOW_WEB_BASE=/recoow` 后，页面、导航和静态资源统一使用 `/recoow/`，API 仍访问来源根路径下的 `/api/`。

子路径构建与验收使用相同环境变量：

```sh
RECOOW_WEB_BASE=/recoow pnpm build
RECOOW_WEB_BASE=/recoow RECOOW_TEST_BACKEND=1 RECOOW_API_URL=http://127.0.0.1:8080 pnpm test:browser
```

Nginx 配置示例：

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $http_host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_cache off;
}

location /_app/immutable/ {
    try_files $uri =404;
    add_header Cache-Control "public, max-age=31536000, immutable";
}

location / {
    try_files $uri /index.html;
    add_header Cache-Control "no-cache";
}
```

保留 API 的 `Cache-Control: no-store`，不在代理、Service Worker 或 CDN 缓存账号接口。反向代理保留浏览器的 Host，使后端正确校验登录与退出请求的 Origin；跨源部署需另行配置，不能直接套用此示例。

部署在 `/recoow/` 时，将 `build/` 的内容放入发布目录下的 `recoow/` 子目录，再将以下位置加入已有 HTTPS server。`/opt/recoow-web/current` 指向对应发布目录，其他网站路径沿用原配置：

```nginx
location = /recoow { return 308 /recoow/; }

location ^~ /recoow/_app/immutable/ {
    root /opt/recoow-web/current;
    try_files $uri =404;
    add_header Cache-Control "public, max-age=31536000, immutable";
}

location ^~ /recoow/ {
    root /opt/recoow-web/current;
    try_files $uri $uri/ /recoow/index.html;
    add_header Cache-Control "no-cache";
}
```
