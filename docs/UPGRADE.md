# 升级运行时镜像（Node / dsh）检查清单

镜像里换 Node 或 dsh 版本时，App 侧有几处**硬编码假设**。逐条过一遍，能挡住大部分「装得上、跑不起来」。

## 当前基线

| 项 | 值 | 记在哪 |
|---|---|---|
| Node | 24.22.1 (`/opt/nodejs/node-v24.22.1-linux-arm64`) | 镜像 |
| dsh | 0.2.2-alpha.2 (`/usr/local/bin/dsh` → `lib/bin.js`) | 镜像 |
| 镜像代次 | `LAYOUT_VERSION = "10"` | `RuntimeInstaller.java` |
| 解压条目数 | `ROOTFS_ENTRIES = 62509` | `RuntimeInstaller.java` |
| 镜像发布 | `runtime-v10`，sha256 见 `release.yml` | `.github/workflows/release.yml` |

## 升级步骤

1. arm64 环境（Termux + proot-distro 亦可）装 Node 到 `/opt/nodejs`，`npm i -g pnpm@11 @deepseek-ai/dsh@<版本>`；
2. `PRUNE=1 scripts/pack-runtime.sh <rootfs-dir>`（自动删 `.d.ts`/`.map`/test/docs/其他平台预编译，并 `gzip -9`）；
3. `LAYOUT_VERSION` 加 1，同时按新镜像的 `tar -tzf rootfs.bin | wc -l` 更新 `ROOTFS_ENTRIES`；
4. `GITHUB_TOKEN=... scripts/publish-runtime.sh --update-workflow`；
5. 本地 `assembleRelease` → `adb install -r` → 走一遍下面的冒烟清单。

## App 对镜像的硬依赖

| 假设 | 代码位置 | 破了会怎样 |
|---|---|---|
| stdout 里出现 `dsh web: http://…` | `DshService.URL_PATTERN` | 一直等到超时，启动被判失败 |
| `dsh` 支持 `--port` / `--no-open` | `DshService.startServer` | 端口/浏览器行为失控 |
| `/usr/local/bin/dsh` 是软链 | 启动命令、`res-hooks`（只缓存 `/opt` 下路径） | 换成实体文件后缓存策略要重看 |
| profile 在 `$DSH_HOME/profiles/web`，清单字段 `dsh.profile.bundles` | `PluginSeed` | 内置插件不生效 / 旧包清理失效 |
| 凭据在 `$DSH_HOME/.credentials.yaml` | `netReport` 的 `/net` | 诊断接口读不到 key（不影响对话） |
| 模型默认名 `deepseek-chat` | dsh 插件自身；中转/自建端点常不认 | 报 4xx「model not available」 |

## 冒烟清单（真机）

- [ ] 冷启动 `harness ready`：首次（重新解压）< 45 s，第二次 < 12 s；
- [ ] `GET /diag`：timeline 完整，无 `never ready`；
- [ ] `GET /net`：`messages` 与 `container` 都 200（内网端点用 `resolv.conf` 绑定后应能解析）；
- [ ] `GET /log`：无 `ERR_MODULE_NOT_FOUND`；
- [ ] Web UI 能发一条消息并收到回复；
- [ ] 控制面板「检查更新」返回 `latest`；
- [ ] `POST /lease?owner=test&ttl=45` 后 `GET /leases` 有记录，过期后自动消失。

## 裁剪注意

- **`skills/` 下的 `.md` 不能删**：技能定义和参考资料就是 markdown，删了技能目录会空；
- `.d.ts`、`.map`、`.pdb`、`test/`、`docs/`、`win32-*`/`darwin-*` 预编译可以删；
- 裁剪后用 `gzip -t` 校验，并确认 `/usr/local/bin/dsh`、`dsh/lib/bin.js`、`profiles/web/package.json`、`dsh-app-boot/lib/index.js` 四个关键条目还在。

## 解析缓存（res-cache）

- 首次启动记录 `(specifier, parentURL) → URL` 到 `files/boot/res-cache.jsonl`，之后启动直接命中；只缓存 `file:///opt/` 前缀的路径，用户插件目录不缓存；
- **镜像更新必须清缓存**：`RuntimeInstaller` 在写完 `.ready` 后删除该文件；如果改了缓存目录或前缀判断，这里要同步；
- 缓存异常时删掉 `files/boot/res-cache.jsonl` 重启即可，不影响正确性，只影响速度。
