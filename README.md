# DSH for Android

DeepSeek Harness 的 Android 壳：把 dsh 运行时（Ubuntu arm64 + Node 24 + @deepseek-ai/dsh）打包进 APK，
安装后**无需 Termux、无需联网**即可在手机上运行，并用全屏 WebView 展示界面。

## 架构

```
MainActivity (WebView + 启动进度)
   │ 读 DshService.currentUrl / 监听 READY、PROGRESS 广播
   ▼
DshService (前台服务, wakelock, 开机自启)
   │ 首次启动：解压 assets/rootfs.bin → files/runtime（1.2 GB）
   ├─ 迁移上一版 profile（保留会话/配置）
   ▼
proot -0 -r <rootfs> … /usr/local/bin/dsh web --no-open --port 3080
   │ stdout 里的 "dsh web: http://…?token=…"
   ▼ 广播给 Activity → WebView 加载
```

关键约束：**targetSdk 28**。Android 10+ 禁止应用执行自己写出的可执行文件，targetSdk ≥ 29 会失去这项能力，
而 proot 与运行时二进制都在应用私有目录里。代价是无法上架 Google Play，只能侧载分发。

## 目录

| 路径 | 说明 |
|---|---|
| `app/src/main/java/dev/dsh/android/` | MainActivity、DshService、RuntimeInstaller、UserData、BootReceiver |
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

## 已知限制

- **APK ~307 MB**，首次安装解压约 20 s，之后冷启动约 20 s（proot 下模块解析的 stat 开销）。
- **签名**：当前用 debug 签名，正式分发需替换 keystore。
- **登录**：OAuth 页面由系统浏览器打开（WebView 里渲染不出来），授权回调打回 `127.0.0.1:3080`。
- **插件**：不内置；通过「设置 → 插件 → 添加插件」安装，profile 随镜像升级迁移而保留。
- **工作区**：`/sdcard/DSH`，系统文件管理器可直接读写。

## License / 第三方

随包分发 Ubuntu base rootfs（其自身许可）、proot（GPLv2）、Node.js（MIT）与 DeepSeek Harness（MIT）。
再分发时需保留各自许可证与源码获取方式。
