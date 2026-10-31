# Android APK 构建与分发

以下流程面向直接分发的完整 APK。Google Play 的 AAB、商店表单、审核和正式发布需按目标渠道另行验证。构建校验不会上传文件或创建签名身份。

## 构建产物检查

在 `android/` 中运行：

```sh
python3 -m unittest discover -s build-tools -p 'test_verify_release.py'
./gradlew :app:verifyReleaseArtifact
```

任务通过 AGP 的 APK 产物接口先构建 Release，再检查实际打包内容：生产包名与 SDK 范围、版本、权限边界、导出组件、调试/测试标记、可选 Vulkan、Baseline Profile、原生库压缩与对齐。64 位 ELF 的加载段必须支持 16 KB 页；APK 另由 SDK `zipalign -P 16` 检查。32 位库按 4 KB 要求检查。

报告为 `app/build/reports/release-artifact/release.json`。默认 `mode=build-only`，不证明签名、安装升级、16 KB 真机运行、商店审核或全部业务验收已经通过。原生运行时兼容性仍需设备测试。

APK 位于 `app/build/outputs/apk/release/`，以该目录的 `output-metadata.json` 指定的文件为准，避免拿到旧的已签名或未签名副本。R8 映射文件位于 `app/build/outputs/mapping/release/mapping.txt`，应与对应 APK、SHA-256、版本和源码提交一起归档。

## 签名与升级校验

发布环境通过受控凭据存储提供 `RECOOW_KEYSTORE`、`RECOOW_KEYSTORE_PASSWORD`、`RECOOW_KEY_ALIAS`、`RECOOW_KEY_PASSWORD`。完整设置后 Gradle 生成签名 Release；全部未设置时仅生成未签名 APK，部分缺失时构建拒绝继续。

签名密钥和密码不得提交或写入构建日志。密钥应保留受控离线备份，并确认能在隔离环境恢复签名。已安装版本的升级须沿用原签名身份；不要用新密钥替代丢失的密钥。首次分发前，将证书 SHA-256 独立记录并核对，后续校验使用该固定值，不从待验证 APK 自动取得“期望值”。

指定本机实际路径及已记录的证书指纹，运行：

```sh
python3 build-tools/verify_release.py \
  --apk /path/to/app-release.apk \
  --sdk /path/to/Android/sdk --build-tools 37.0.0 \
  --min-sdk 26 --target-sdk 36 \
  --certificate-sha256 YOUR_RECORDED_CERTIFICATE_SHA256 \
  --previous-apk /path/to/previously-distributed.apk \
  --report /path/to/release-verification.json
```

首次分发可省略 `--previous-apk`。签名模式调用 `apksigner verify` 并检查固定证书；拒绝 Android Debug 证书。升级检查还要求包名不变且 `versionCode` 严格递增。该流程按单一、不轮换的签名身份校验；若采用密钥轮换，应另行验证签名谱系及各 Android 版本的升级路径。

发布之前还需在保留数据的安装上执行覆盖升级，检查记录、附件、偏好、提醒和备份恢复。不要用清空数据后的安装代替升级验收。`versionName` 用于展示；商店及安装升级顺序由 `versionCode` 决定。

## 对外资料

每个分发版本应附带实际版本号、最低系统/硬件要求、APK 校验值、变更与已知限制、[数据与权限说明](PRIVACY.md)，以及同步服务器运营方的联系和数据删除方式。不得将测试签名、模拟器结果或尚未执行的检查表述为正式渠道验收。

地图采用同版本 MapLibre 的 Vulkan/OpenGL 双后端，支持 Vulkan 的设备默认使用 Vulkan，其余设备使用 OpenGL ES。此选择保留地图、轨迹和回放功能；它不免除旧设备或厂商图形驱动的运行验证。
