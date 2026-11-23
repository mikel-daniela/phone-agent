# apk/

这里放**能直接装**的发行包。

- 当前版本：`0.1.0-m2`（versionCode 3，minSdk 见 `android/app/build.gradle.kts`）
- 下载：直接点本目录下的 `PocketAgent-0.1.0-m2.apk`
- 校验：`SHA256SUMS.txt` 里有这个包的 SHA-256；`certutil -hashfile <包名> SHA256`（Windows）
  或 `sha256sum <包名>`（Linux / macOS）对一下
- 版本说明：`docs/发布/0.1.0-m2.md`

## 为什么包在仓库里、不在 Releases 页

GitHub Releases 的资产要用带 `contents:write` 的 token 上传；这台机器上只有
SSH 部署密钥（能 push、打不了 API），所以发行包直接提交进这个目录。单个文件
63 MB，在 GitHub 的 100 MB 单文件上限以内。只留最新两三个版本，旧的删掉。

## 自己构建

```bash
cd android
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk（未签名）
```

release 变体**不配置签名**（`build.gradle.kts` 里没有 `signingConfig`）——
签名材料不进仓库。要用自己的 Key 签：

```bash
apksigner sign --ks <你的.jks> --ks-key-alias pocketagent \
  --out PocketAgent-<版本>.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

⚠️ 换签名会导致**已装用户无法覆盖安装**（Android 按签名判定是不是同一个应用），
所以公开发出去的包必须一直用同一把 Key。

## 这个版本里有什么

见 `docs/发布/0.1.0-m2.md`。
