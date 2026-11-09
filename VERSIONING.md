# 版本与发布

`versions.json` 是应用发布版本的唯一来源。Android、iOS、后端和 Web 分别发布，互不要求版本号相同。接入时 Android 为 `0.6.0`，iOS 为 `0.1.0`，后端与 Web 从 `0.0.1` 开始。

```sh
python3 scripts/version.py show
python3 scripts/version.py check
python3 scripts/version.py bump android patch
python3 scripts/version.py bump ios minor
python3 scripts/version.py bump backend patch
python3 scripts/version.py bump web patch
```

`bump` 只修改所选端的版本及其生成文件，不提交、不打标签、不推送。日常构建和 `check` 不修改版本。新增功能升 minor，修复升 patch，不兼容的应用发布升 major；是否升版由发布者决定，不根据提交信息自动猜测。

生成文件 `android/version.properties`、`ios/Version.xcconfig`、`backend/internal/buildinfo/version_generated.go` 随版本提交；Web 的版本同步到 `web/package.json`。不要直接编辑这些版本字段。手动调整 `versions.json` 后运行 `python3 scripts/version.py sync`，CI 用 `check` 拒绝漂移。iOS 主应用和 Widget 的 Debug/Release 共用同一份配置。

Android 更新发布使用 `android/v<version>` 标签，后端与 Web 的现有标签分别使用 `backend/v<version>`、`web/v<version>`。标签必须与该提交的配置一致，每个已发布标签只使用一次。iOS 开发签名自用安装不创建或推送标签，以版本配置和源码提交记录产物。一个端修复问题只升该端；已有版本不替换 APK 或运行包。

## 构建编号

移动端发布流水线使用 `buildBase + GITHUB_RUN_NUMBER`。同一 workflow 的重跑保持编号，下一次运行递增；`GITHUB_RUN_ATTEMPT` 不参与编号。语义版本升版时不重置 `buildBase`。每个端只设一条正式发布 workflow；迁移或重建 workflow 导致计数重置时，先提高 `buildBase`，发布前还要与已发布的最高编号比较，拒绝倒退。

Android 将结果作为 `versionCode`，通过 `RECOOW_ANDROID_VERSION_CODE` 注入 Gradle，最大值为 2100000000。本地构建使用配置中的基线。

## Android 发布

正式发布前提交版本配置、生成文件及 `android/releases/<version>.md`，再推送对应的 `android/v<version>` 标签。发布流水线完成检查和签名 APK 校验后上传安装包及应用内更新信息。

手动触发时必须指定发布标签，例如 `gh workflow run android-release.yml --ref android/v0.7.0`。从默认 `main` 分支触发只执行检查，跳过发布。

未发布成功的任务可以在 Actions 中选择 `Re-run failed jobs`，或用 `gh run rerun <run-id> --failed` 重跑。重跑保持原构建编号；已公开的版本及标签不能覆盖，后续更新应使用新版本。

## iOS 发布

iOS 使用开发签名自用安装，提交版本配置后构建和导出安装包，不打 tag。iOS 将序号转换为满足 `CFBundleVersion` 位数限制的三段数字：序号 1 为 `1.0.0`，100 为 `1.0.99`，101 为 `1.1.0`，10001 为 `2.0.0`；与应用的语义版本无关。未来 iOS 构建 CI 可使用独立运行编号：

```sh
ios_build=$(python3 scripts/version.py show ios --field build --run-number "$GITHUB_RUN_NUMBER")
xcodebuild -project ios/recoow.xcodeproj -scheme recoow -configuration Release \
  CURRENT_PROJECT_VERSION="$ios_build" archive
```

命令行构建设置同时覆盖主应用与 Widget，不回写工程文件。iOS 签名、商店上传仍按独立发布流程处理。

## 后端

```sh
bash scripts/package-backend.sh
./recoow-api --version
```

包名含后端版本；`build-info.json` 保存版本、标签、Git 提交和工作区是否有未提交改动，纳入 SHA256SUMS。二进制的 `--version` 和启动日志也包含版本及提交信息。源码直接 `go build` 使用生成的版本常量，并尽可能读取 Go 自带的 Git 构建信息。

后端的 GitHub Release 应使用 `--latest=false`；iOS 自用安装包保留本地。仓库的 Latest 留给 Android 稳定 APK，供 Android 应用内更新查找。应用版本、同步协议版本、数据表版本及备份格式版本分别维护，升应用版本不会自动修改数据协议。

## Web 发布

Web 使用独立语义版本，不使用移动端构建编号。发布前提交版本配置与 `web/package.json`，静态产物由 `pnpm build` 生成到 `web/build/`，不纳入 Git。标签使用 `web/v<version>`，通过 `python3 scripts/version.py ci web --tag web/v<version>` 校验。Web 与后端按兼容契约部署，仓库 Latest 继续留给 Android。
