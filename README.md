# DSH for Android

DeepSeek Harness 的 Android 壳：把 dsh 运行时（Ubuntu arm64 + Node 24 + @deepseek-ai/dsh）打包进 APK，
安装后**无需 Termux、无需联网**即可在手机上运行，并用全屏 WebView 展示界面。

## 下载与安装

现成安装包在 [Releases](https://github.com/anwesc/dsh-android/releases)：

```
https://github.com/anwesc/dsh-android/releases/download/v1.0.3/dsh-1.0.3-release.apk
```

- **只能侧载**（文件管理器点开安装 / `adb install -r dsh-1.0.1-release.apk`），无法上架 Google Play。
- 首次启动解压约 20 秒（1.2 GB 运行时），之后冷启动约 20 秒；服务常驻时打开即用。
- 小米/HyperOS 用 adb 安装新签名包时若报 `INSTALL_FAILED_USER_RESTRICTED`，
  需在开发者选项里打开「USB 安装」，或直接在手机上点 APK 安装。
- Android 16 上 adb 安装大包前建议先 `adb shell input keyevent KEYCODE_WAKEUP`，锁屏状态下会被自动拒绝。

## 架构

```
MainActivity (WebView + 启动进度)
   │ 读 DshService.currentUrl / 监听 READY、PROGRESS 广播
   ▼
DshService (前台服务, wakelock, 开机自启)
   │ 首次启动：解压 assets/rootfs.bin → files/runtime（1.2 GB）
   ├─ 迁移上一版 profile（保留会话/配置）
   ▼
proot -0 -r <rootfs> … /usr/local/bin/dsh web --no-open --port <空闲端口>
   │ stdout 里的 "dsh web: http://…?token=…"
   ▼ 广播给 Activity → WebView 加载
```

关键约束：**targetSdk 28**。Android 10+ 禁止应用执行自己写出的可执行文件，targetSdk ≥ 29 会失去这项能力，
而 proot 与运行时二进制都在应用私有目录里。代价是无法上架 Google Play，只能侧载分发。

## 目录

| 路径 | 说明 |
|---|---|
| `app/src/main/java/dev/dsh/android/` | MainActivity、DshService、RuntimeInstaller、UserData、BootReceiver、PluginSeed |
| `app/src/main/assets/plugin-seed/` | 每次启动被 `PluginSeed` 拷进 profile 的内置插件（生成目录，见下） |
| `app/src/main/assets/mobile.css` | 窄屏修补（第三方桌面式插件的溢出/换行） |
| `app/src/main/assets/runtime/` | proot 与其动态库、`libexec/proot/loader` |
| `app/src/main/assets/rootfs.bin` | gzip 压缩的 rootfs（**不入库**，见下） |
| `artwork/dsh.jpg` | 图标源图（1254×1254），`res/mipmap-*` 由它生成 |
| `dist/` | 构建产物（**不入库**） |

## 镜像自带工具

`curl`、`git`、`python3`、`xz`、`tar`、`gzip` 已预装（插件市场安装插件依赖 curl/git）。
未装编译器（gcc/make），需要 node-gyp 的插件请自行 `apt-get install build-essential`。

## 构建

需要 JDK 17+（Android Studio 自带 JBR 即可）与 Android SDK（platform 36、build-tools 36.1.0）。

```bash
export JAVA_HOME="/path/to/Android Studio/jbr"   # JDK 21
./gradlew assembleDebug                          # 首次会下载 Gradle 8.13
```

也可以用 Android Studio 直接打开工程。`local.properties` 里的 `sdk.dir` 指向本机 SDK，不入库。

## 重新生成运行时镜像

`rootfs.bin` 有 300+ MB，不放在 Git 里。生成步骤（需要一个 Linux arm64 环境，Termux + proot-distro 亦可）：

1. 准备 Ubuntu 24.04 arm64 rootfs，安装 Node 24 到 `/opt/nodejs`，`npm i -g pnpm@11 @deepseek-ai/dsh`；
2. 确保 web profile 的 `.npmrc` 指向可达的 npm 源（国内用 `https://registry.npmmirror.com`）；
3. 清理缓存后打包：

```bash
tar --hard-dereference -cf - -C <rootfs> . | gzip -1 > rootfs.bin
```

4. 放到 `app/src/main/assets/rootfs.bin`，把 `RuntimeInstaller.LAYOUT_VERSION` 加 1（触发老用户重新解压）。

`--hard-dereference` 是必需的：Android 禁止应用创建硬链接，镜像里的硬链接必须展开成实体文件。

## 签名与发布

`keystore.properties`（已 gitignore）保存签名参数，`dsh-release.jks` 是签名密钥：

```bash
./gradlew assembleRelease   # 输出 app/build/outputs/apk/release/app-release.apk
```

**务必备份 `dsh-release.jks` 与 `keystore.properties`**：换签名后无法覆盖安装，
用户必须先卸载（本地运行时、会话、插件都会丢失）。

debug 与 release 签名不同，两者不能互相覆盖：调试用 `assembleDebug`，分发用 `assembleRelease`。

## 内置插件（plugin-seed）

`plugins/qqbot-guard/` 是随 App 分发的补丁插件：装了 QQ 通道后，群里出现关键词也能触发对话。
源码在仓库里，**装进 APK 的副本是生成物**：

```bash
scripts/sync-plugin-seed.sh          # plugins/qqbot-guard → assets/plugin-seed/dsh-qqbot-guard
scripts/sync-plugin-seed.sh --check  # CI 用：副本过期则非零退出
```

启动时 `PluginSeed.install()` 在 `SafetyNet.beginBoot()` 之前做三件事（幂等）：

1. 把资产拷进 `rootfs/root/.dsh/profiles/web/node_modules/dsh-qqbot-guard`；
2. 把 `dsh-qqbot-guard` 并入 profile `package.json` 的 `dsh.profile.bundles`；
3. 首次启动写 `/root/.dsh/qqbot-guard.json`（关键词 `大肥鱼`/`肥鱼`/`鱼鱼`），已存在则不覆盖。

故意不写 `dependencies`：`file:` 依赖会绑死容器路径，而 loader 只按名字从 `node_modules` 解析。
升级时 `RuntimeInstaller.migrateProfile()` 会用旧 profile 覆盖新解包的 profile，seed 在之后跑，正好补回。

插件按**自身安装位置**反推 profile 的 `node_modules`（`…/profiles/web/node_modules/dsh-qqbot-guard` →
`…/profiles/web/node_modules/@tencent-connect/dsh-qqbot`），所以 qqbot 装在 profile 里时不需要在配置里写
`qqbotPackage`；自动探测落空的日志会列出试过的所有路径。回归用例：`node --test plugins/qqbot-guard/`。

## 让助手重启服务

装完插件后不必手动重启 App。服务在 `127.0.0.1:3081` 暴露控制端口（仅 loopback）：

| 请求 | 作用 |
|---|---|
| `POST /reload` | 让 WebView 重新加载页面（token 不变，最轻） |
| `POST /restart` | 重启 `dsh web`，新 token 自动推给页面 |
| `GET /` | 返回当前 URL |
| `POST /backup` | 把当前 profile 配置记为 last-good |
| `POST /restore` | 回滚到 last-good 并重启（启动失败时 App 弹窗请求确认，不自动覆盖） |
| `GET /update/auto` | 查询是否自动检查更新 |
| `POST /update/auto?enabled=false` | 关闭启动时的自动更新检查 |
| `POST /export` | 把家目录镜像到 `/sdcard/DSH/dsh` |
| `POST /update` | 检查 App 新版本（下载并拉起安装器） |

助手在容器里直接执行即可（镜像自带 curl）：

```bash
curl -s -X POST http://127.0.0.1:3081/reload
curl -s -X POST http://127.0.0.1:3081/restart
```

端口被占用时（例如旧进程残留），服务会在 3080–3099 里挑第一个空闲端口（跳过控制端口 3081），URL 也随之变化。

## 控制面板

控制面板入口（无悬浮按钮）：**启动页长按**，或启动后**长按页面左侧图标栏**；也可 `adb shell am start -n dev.dsh.android/.ControlActivity`：

| 项 | 作用 |
|---|---|
| 启动时自动检查更新 | 开关；关掉后只保留手动 `POST /update` |
| 刷新页面 / 重启服务 | 对应 `POST /reload`、`POST /restart` |
| 检查更新 | `POST /update`，下载并拉起系统安装器 |
| 导出家目录 | `POST /export` |
| 备份当前配置 / 回滚 | `POST /backup`、`POST /restore` |

## CI 出包

`.github/workflows/release.yml`：打 `v*` tag 时自动下载运行时镜像、签名并附加 APK 到 Release。

首次配置需要在仓库 Settings → Secrets and variables → Actions 里加 4 个 secret：

| Secret | 值 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 dsh-release.jks` 的整行输出 |
| `KEYSTORE_PASSWORD` | `keystore.properties` 里的 `storePassword` |
| `KEY_ALIAS` | `keystore.properties` 里的 `keyAlias` |
| `KEY_PASSWORD` | `keystore.properties` 里的 `keyPassword` |

PowerShell 取 base64：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("dsh-release.jks")) | Set-Clipboard
```

发布新版本（先改 `versionCode`/`versionName` 再提交）：

```bash
git tag v1.0.3
git push origin refs/tags/v1.0.3   # --follow-tags 不会推轻量标签
```

镜像更新时：把新镜像传到 `runtime-vN` Release，并同时改 `RuntimeInstaller.LAYOUT_VERSION`
和 workflow 里的 `RUNTIME_TAG`/`RUNTIME_SHA256`。


### 为什么用 lint 而不是模拟器冒烟

运行时是 arm64 + proot 的完整 Linux 用户态，x86_64 模拟器跑不起来；GitHub 的 arm64 runner 没有嵌套虚拟化，起不了 Android 模拟器。
真正能在 CI 里挡住这类问题的是 lint 的 NewApi 检查：它按 minSdk 校验每个调用的 API 级别，`InputStream.readAllBytes`（API 33）这种「编译过、真机崩」的问题会被直接判错。

`.github/workflows/ci.yml` 在每次 push/PR 跑 `assembleDebug` + `lintDebug`；`release.yml` 在出包前也跑一次 `lintRelease`。真机验证仍需人工完成一次（安装 → 首次解压 → 页面可用）。

### 更新运行时镜像

1. 在 arm64 环境重新生成 `rootfs.bin`（见上一节），放到 `app/src/main/assets/`；
2. 如果是新的镜像代次，先把 `RuntimeInstaller.LAYOUT_VERSION` 加 1；
3. 发布并同步 workflow：

```bash
GITHUB_TOKEN=ghp_xxx scripts/publish-runtime.sh --update-workflow
```

脚本会：从 `LAYOUT_VERSION` 推出 tag（如 `runtime-v10`）→ 计算 sha256 → 建/复用 Release → 上传 `rootfs.bin` → 改写 `release.yml` 里的 `RUNTIME_TAG` / `RUNTIME_SHA256`。
加 `--dry-run` 只打印计划；`--tag`、`--repo`、`--draft` 可覆盖默认值。
## 已知限制

- **APK ~350 MB**，首次安装解压约 20 s，之后冷启动约 20 s（proot 下模块解析的 stat 开销）。
- **签名**：分发包由 CI 用 `KEYSTORE_BASE64` 等 secrets 签名；本地没有 `keystore.properties` 时 `assembleRelease` 会退回 debug 签名，只能自己装，不能覆盖正式版。
- **登录**：OAuth 页面由系统浏览器打开（WebView 里渲染不出来），授权回调打回 `127.0.0.1:3080`。
- **插件**：不内置；通过「设置 → 插件 → 添加插件」安装，profile 随镜像升级迁移而保留。
- **工作区**：容器内 `/root/work`，绑到手机 `内部存储/DSH/work`，系统文件管理器可直接读写。

## License / 第三方

随包分发 Ubuntu base rootfs（其自身许可）、proot（GPLv2）、Node.js（MIT）与 DeepSeek Harness（MIT）。
再分发时需保留各自许可证与源码获取方式。
