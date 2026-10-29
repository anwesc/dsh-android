# DSH on Android — 运行环境须知

运行在 Android 上的 Ubuntu arm64 容器里（proot），已预装 curl、git、python3。**你是 root**，但只能看到容器内的文件。

## 目录
- 工作区 `/root/work` = 手机 `内部存储/DSH/work`
- DSH 家目录 `/root/.dsh`（会话/插件/配置）：只读镜像到手机 `内部存储/DSH/dsh`，
  由启动时或 `curl -s -X POST http://127.0.0.1:3081/export` 刷新；它不是挂载，写入请改容器里的原件

## 装完插件后如何重启
不需要用户手动退出 App，直接执行：

```bash
curl -s -X POST http://127.0.0.1:3081/reload   # 只刷新页面（最轻，先试这个）
curl -s -X POST http://127.0.0.1:3081/restart  # 重启 dsh web，约 20 秒
curl -s http://127.0.0.1:3081/                 # 查当前 URL

# 配置改坏了（导致启动失败）时：
curl -s -X POST http://127.0.0.1:3081/backup    # 手动把当前配置记为"可用"
curl -s -X POST http://127.0.0.1:3081/restore   # 回滚到上一次成功启动的配置并重启

# 检查 App 自身是否有新版本（会下载并拉起安装器）
curl -s -X POST http://127.0.0.1:3081/update

# 关掉/打开「启动时自动检查更新」
curl -s -X POST 'http://127.0.0.1:3081/update/auto?enabled=false'
curl -s http://127.0.0.1:3081/update/auto
```

启动失败时（进程退出但没有输出 URL），App 会弹窗请求确认是否回滚到上一次成功启动的配置，
不会自动覆盖——回滚会丢掉期间对配置的修改。用户在弹窗里点「回滚并重启」才执行，也可以直接 POST /restore。

## pip 安装失败时
容器里没有编译器，网络也常失败，按顺序尝试：

1. `pip install --no-deps <pkg>`（跳过依赖解析）
2. 下载后手工拷贝：`pip download <pkg> -d /tmp/pip`，解包后把包目录复制进
   `$(python3 -c "import site;print(site.getsitepackages()[0])")`
3. 用发行版包：`apt-get install -y python3-<name>`
4. 换国内源：`pip install -i https://pypi.tuna.tsinghua.edu.cn/simple <pkg>`

## 控制面板（等价于下面的接口）

App 内长按可调出面板：**启动页任意处**，或启动后**长按页面左侧图标栏**。面板里的每个动作都对应一个 HTTP 接口，你可以直接调用：

| 动作 | 命令 | 返回 |
|---|---|---|
| 刷新页面 | `curl -s -X POST http://127.0.0.1:3081/reload` | `{"status":"reloading"}` |
| 重启服务 | `curl -s -X POST http://127.0.0.1:3081/restart` | `{"status":"restarting"}`，约 20 秒 |
| 检查更新 | `curl -s -X POST http://127.0.0.1:3081/update` | `latest` / `update` / `offline` |
| 导出家目录 | `curl -s -X POST http://127.0.0.1:3081/export` | `{"status":"exporting"}` |
| 备份配置 | `curl -s -X POST http://127.0.0.1:3081/backup` | `{"status":"backed-up","at":"时间与路径"}` |
| 回滚配置 | `curl -s -X POST http://127.0.0.1:3081/restore` | `{"status":"restored","from":"时间与路径"}` |
| 自动更新开关 | `curl -s -X POST 'http://127.0.0.1:3081/update/auto?enabled=false'` | `{"enabled":false}` |
| 查当前地址 | `curl -s http://127.0.0.1:3081/` | `{"url":"http://127.0.0.1:3080/?token=…"}` |

注意：

- 改完配置先 `/reload`，无效再 `/restart`；
- **不要主动 `/restore`**：回滚会丢掉用户期间的改动，启动失败时 App 会弹窗让用户自己确认；
- 备份位置：`/data/user/0/dev.dsh.android/files/safety/last-good`，镜像在 `/sdcard/DSH/backup`；
- 每次成功启动都会自动覆盖备份，手动 `/backup` 只是立刻打一次点。

## 改配置前必须先备份

修改任何配置文件（`cordis.yml`、`cordis.patch.yml`、`package.json`、lock、`.npmrc`、`compatibility.json`）**之前**，先执行：

```bash
curl -s -X POST http://127.0.0.1:3081/backup
```

返回 `{"status":"backed-up","slot":"manual-20261029-172927","at":"/storage/emulated/0/DSH/backups/manual-20261029-172927"}`，请在回复里报告这个 slot。

- 手动备份是独立槽位，**不会被启动时的自动快照覆盖**，保留最近 5 份；
- 查看可用备份：`curl -s http://127.0.0.1:3081/backups` → `{"slots":[...],"last-good":true}`；
- 回滚到指定备份：`curl -s -X POST 'http://127.0.0.1:3081/restore?slot=manual-20261029-172927'`；
- 回滚到自动快照：`curl -s -X POST http://127.0.0.1:3081/restore`。

流程：备份 → 改配置 → `/reload` → 不行再 `/restart` → 仍失败则报告用户，**不要自行回滚**（回滚会丢用户改动，需用户确认）。

## 装插件
优先用 App 界面（设置 → 插件）。命令行等价：

```bash
dsh plugin --profile web add <包名>
curl -s -X POST http://127.0.0.1:3081/restart
```
