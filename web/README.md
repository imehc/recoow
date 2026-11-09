# recoow Web

基于 Svelte 5、SvelteKit、TypeScript、shadcn-svelte 与 ECharts 的云端只读入口。提供登录、21 类实体查询、基础统计、变更历史、媒体预览和设备查看。

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
pnpm exec playwright install chromium
pnpm test:browser
pnpm verify
```

格式化同时覆盖 Web 与公共 `openapi/openapi.yaml`，YAML 保持多行结构。实体元数据和 API 类型从公共 OpenAPI 生成，生成结果不纳入 Git。

浏览器检查包含桌面和移动布局。使用已安装的 Chrome 时可设置 `RECOOW_BROWSER_CHANNEL=chrome`。与 Go 服务的集成检查使用独立测试库，开启测试账号注册后执行：

```sh
RECOOW_TEST_BACKEND=1 RECOOW_API_URL=http://127.0.0.1:8080 pnpm test:browser
```

如测试后端设置了注册口令，还需要 `RECOOW_TEST_BOOTSTRAP_TOKEN`。该检查创建临时账号，不应对生产服务执行。

## 数据与界面偏好

Web 只显示服务端已收到的数据，不保存本地业务数据库或离线写入队列。查询仅在内存中缓存，退出、会话过期、账号切换和数据集世代变化会清理缓存。页面重新聚焦或恢复联网时重新验证身份与世代，并刷新查询。

浅色、深色、跟随系统，以及简体中文、English、跟随系统可随时切换。`localStorage` 仅保存主题和语言偏好，不保存账号、Token 或业务记录。时间按浏览器时区显示，客户端修改时间与服务端接受时间分别展示；缺失历史信息不推算。整数金额保持精度，界面语言不会改变原始记录内容或货币单位。

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

`pnpm build` 输出 `build/`。将静态文件与 Go API 部署在同一 HTTPS 来源，站点根路径指向静态目录，`/api/` 代理到 Go，其他未匹配的页面路径回退到 `index.html`。目前支持来源根路径部署。

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
