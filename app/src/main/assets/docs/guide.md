# DSH on Android — 运行环境须知

运行在 Android 上的 Ubuntu arm64 容器里（proot），已预装 curl、git、python3。**你是 root**，但只能看到容器内的文件。

## 目录
- 工作区 `/root/work` = 手机 `内部存储/DSH/work`
- 交换目录 `/root/home` = 手机 `内部存储/DSH/home`
- DSH 家目录 `/root/.dsh`（会话/插件/配置）：只读镜像到手机 `内部存储/DSH/dsh`，
  由启动时或 `curl -s -X POST http://127.0.0.1:3081/export` 刷新；它不是挂载，写入请改容器里的原件

## 装完插件后如何重启
不需要用户手动退出 App，直接执行：

```bash
curl -s -X POST http://127.0.0.1:3081/reload   # 只刷新页面（最轻，先试这个）
curl -s -X POST http://127.0.0.1:3081/restart  # 重启 dsh web，约 20 秒
curl -s http://127.0.0.1:3081/                 # 查当前 URL
```

## pip 安装失败时
容器里没有编译器，网络也常失败，按顺序尝试：

1. `pip install --no-deps <pkg>`（跳过依赖解析）
2. 下载后手工拷贝：`pip download <pkg> -d /tmp/pip`，解包后把包目录复制进
   `$(python3 -c "import site;print(site.getsitepackages()[0])")`
3. 用发行版包：`apt-get install -y python3-<name>`
4. 换国内源：`pip install -i https://pypi.tuna.tsinghua.edu.cn/simple <pkg>`

## 装插件
优先用 App 界面（设置 → 插件）。命令行等价：

```bash
dsh plugin --profile web add <包名>
curl -s -X POST http://127.0.0.1:3081/restart
```
