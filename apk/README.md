# apk/

发行用的安装包放在 **GitHub Releases**，不放仓库里 —— 这个包 62 MB，
提交进 git 会让以后每次 clone 都拖一遍，而且 git 的差异压缩对它毫无办法。

- 下载页：https://github.com/mikel-daniela/phone-agent/releases
- 当前版本：`0.1.0-m2`（versionCode 3，minSdk 见 `android/app/build.gradle.kts`）
- 签名指纹（SHA-256）见 `SHA256SUMS.txt`

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
  --out PocketAgent-0.1.0-m2.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

⚠️ 换签名会导致**已装用户无法覆盖安装**（Android 按签名判定是不是同一个应用），
所以公开发出去的包必须一直用同一把 Key。

## 这个版本里有什么

见同一次 Release 的说明，或 `docs/发布/` 下的版本说明。
