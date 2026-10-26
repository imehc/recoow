# 生成 iOS 模型资源

```sh
make decision-models-ios
```

# iOS 依赖拉取缓慢 / 卡住

工程通过 SwiftPM 引用远程包（`GRDB.swift`、`Lightbox` 等），网络不佳或本地缓存损坏时，`xcodebuild` 解析依赖会长时间卡住甚至失败。

## 强制重新解析（跳过本地缓存）

```sh
xcodebuild -resolvePackageDependencies -disablePackageRepositoryCache \
  -project ios/recoow.xcodeproj -scheme recoow
```

- `-resolvePackageDependencies`：只解析依赖，把包 clone 到 DerivedData 的 `SourcePackages` 并写回 `Package.resolved`，不编译。
- `-disablePackageRepositoryCache`：跳过本地 git mirror 缓存（`~/Library/Caches/org.swift.swiftpm/repositories`），强制从远端重新 clone。

**适用场景**：报 `Revision ... does not match`、`Unable to resolve package`，或解析长时间无响应，怀疑缓存过期/损坏时。

**注意**：这不是加速参数。全量重新 clone 本身更慢，只在缓存出问题时使用；日常拉取不要加。

## 缓存还不干净就整个删掉

```sh
rm -rf ~/Library/Caches/org.swift.swiftpm
rm -rf ~/Library/Developer/Xcode/DerivedData/recoow-*
```

配合上面的命令重跑一次即可。

## 真正加速：走镜像或 SSH

`-disablePackageRepositoryCache` 解决的是「拉不动」，不是「拉得慢」。持续慢是 GitHub 连通性问题，换源更有效：

```sh
# 用镜像替换 GitHub 地址（一次配置，全局生效）
git config --global url."https://gitclone.com/github.com/".insteadOf "https://github.com/"

# 还原
git config --global --unset url."https://gitclone.com/github.com/".insteadOf
```

若 SwiftPM 未读到该改写（它内部用 libgit2，部分场景不生效），改用代理或把工程依赖地址换成 SSH：

```sh
git config --global url."git@github.com:".insteadOf "https://github.com/"
```

解析完成后可用上面的 `--unset` 回滚配置；不要把 `Package.resolved` 里的版本锁当缓存删掉，重新解析会重新挑版本。
