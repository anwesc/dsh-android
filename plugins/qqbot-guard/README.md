# qqbot-guard

dsh-android 内置的 **qqbot 增强层**（伴侣插件）。qqbot 主体仍由用户从插件市场安装，本插件只做进程内补丁，
不修改 qqbot / SDK 的任何磁盘文件。

## 它补什么

| 编号 | 现象 | 本插件的处理 |
| --- | --- | --- |
| 1 | 群聊必须 @ 才回，关键词（如 `YONGJIE`）被 `mentionGate` 拦下 | 正文命中关键词时前置 `<@appId>`，让 `mentionGate` 判定为被提及 |
| 2 | 关键词触发时看不到上下文（`inbound.js:209` 只在 `wasMentioned` 时拼历史） | 同上：`wasMentioned` 变 true → `buildAgentBody` 自动带上 `[Chat history]` |
| 3 | 不想动 qqbot 的 `dist` 文件（升级即失效） | 全部走进程内原型补丁，升级 qqbot 不影响本插件文件 |

## 为什么和加载顺序无关

`@tencent-connect/qqbot-nodejs` 的 `handleInboundMessage` 在**每次收到消息时**才拼链
（`dist/QQBot.js:387` `const chain = [...this.middlewares, downstream]`），而 `middlewares` 是公开数组
（`dist/QQBot.js:80`）。所以本插件不用抢 `bot.use` 的时机，而是在首条入站消息时
`this.middlewares.unshift(guard)` —— 即使 qqbot 插件先启动、链已经建好，补丁照样生效。

## 安装

1. 包放进 profile 的 `node_modules`（`~/.dsh/profiles/<profile>/node_modules/dsh-qqbot-guard`），
   并在 `package.json` 的 `dependencies` + `dsh.profile.bundles` 里登记；
2. 本包自带 `dsh.bundle.patch`（`cordis.patch.yml`，用 `- insert:` 插入 `qqbot-guard` 行），
   被列入 bundles 后会自动生效——**不要**在 profile 的 `cordis.patch.yml` 里再写一条同 id 的新行；
3. 需要改参数时，二选一：
   - **JSON 后门（手机端推荐）**：编辑 `$DSH_HOME/qqbot-guard.json`
     （App 里是 `/root/.dsh/qqbot-guard.json`，首次启动由 `PluginSeed` 生成）；
   - **profile patch 覆盖**（能写 YAML 时）：

```yaml
- id: qqbot-guard
  name: 'dsh-qqbot-guard'
  config:
    triggers:
      - '大肥鱼'
      - '小鱼'
      - '鱼鱼'
    qqbotPackage: 'C:/Users/<you>/.dsh/profiles/<profile>/node_modules/@tencent-connect/dsh-qqbot'
    verbose: true
```

4. **重启 App**（bundles 变更不走热重载）。

> 关键机制（`@deepseek-ai/dsh-app-boot` 的 `applyEntryPatches`）：普通 patch 只能改**已存在**的 id，
> 找不到就 `warn` 并跳过；新增插件行只能用 `insert`。所以「只写一条 `- id: xxx` 到 profile patch」
> 会静默无效——踩过这个坑。

`qqbotPackage` 建议显式写死：桌面端 App 的 `process.cwd()` 不是 profile 目录，`DSH_HOME` 也未必在环境里，
自动探测可能落空（落空时日志会报「没找到 @tencent-connect/dsh-qqbot」）。

插件声明 `inject: ['agents']`，与 qqbot 插件同级，谁先启动都行。

> APK 内置时不写 `dependencies`：`PluginSeed` 只把目录拷进 `node_modules` 并登记
> `dsh.profile.bundles`。`file:` 依赖会绑死容器路径，而 loader 按名字解析，够用。

### 配置项

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `triggers` | `['大肥鱼', '肥鱼', '鱼鱼']` | 关键词列表，正文包含任一词即视为对我说话 |
| `ignoreCase` | `true` | 关键词是否忽略大小写 |
| `qqbotPackage` | `''` | dsh-qqbot 安装目录或它的 package.json；留空则自动探测 `DSH_HOME` / `cwd` |
| `configFile` | `''` | 外部 JSON 配置路径；留空时依次取 `$DSH_QQBOT_GUARD_CONFIG` → `$DSH_HOME/qqbot-guard.json` → `~/.dsh/qqbot-guard.json` |
| `strictVersion` | `false` | SDK 版本超出白名单 `>=1.0.5 <2.0.0` 时是否放弃安装 |
| `verbose` | `true` | 是否打印诊断日志，就绪日志会带上「配置源=…」 |

配置优先级：`DEFAULT_CONFIG` < 外部 JSON 文件 < cordis 里的 `config`。JSON 解析失败只会打一条
warn 然后忽略，不会让插件挂掉——手机上手改坏了不会变砖。

## 自检

```bash
DSH_QQBOT_DIR=/path/to/node_modules/@tencent-connect/dsh-qqbot node selfcheck.mjs
```

自检会：先按 qqbot 原样建 bot（补丁前）→ 装补丁 → 喂三条消息（闲聊 / 关键词 / 真 @），
断言链头部是 guard、关键词消息被处理、`wasMentioned=true`、模型收到的是**无 `<@` 标记的干净文本**、
闲聊消息仍被拦、群历史已累积。

### 实测结果（qqbot 0.5.3 + qqbot-nodejs 1.0.5，Node 24.21.0）

```
qqbot=0.5.3 sdk=1.0.5 chain=14
PASS  补丁已安装
PASS  链头部是 guard
PASS  补丁未改变原链长度（懒注入时机正确）
PASS  关键词消息被处理
PASS  关键词消息 wasMentioned=true
PASS  模型收到的是干净文本（无 <@ 标记）
PASS  无关键词的消息被门控拦下
PASS  群历史已累积
```

## 必须配套的一项配置

群聊要「只有 @ 或命中关键词才回」，**必须**把 qqbot 的 `requireMention` 设为 `true`
（`cordis.patch.yml` 里 `im-qqbot` 条目）：

```yaml
- id: im-qqbot
  config:
    requireMention: true
```

原因：本插件的做法是把命中关键词的消息「伪装成 @」再交给下游 `mentionGate`；若 `requireMention: false`，
门控对所有群消息一律放行，插件就只剩「补标记」作用，群里每句话都会触发回复。

未 @ 的消息不会丢上下文：`dsh-qqbot/dist/gateway/middleware-setup.js:29` 把 `historyBuffer` 放在
`mentionGate`（`:41`）**之前**，且带 `recordOnSkip: true`，被门控拦下的消息照样计入群历史。

## 已知风险

1. **模块实例一致性**：补丁打在 `@tencent-connect/qqbot-nodejs` 的 `QQBot.prototype` 上，必须和 qqbot 解析到
   同一个文件。本插件按 Node 的 `node_modules` 向上查找规则、从 qqbot 安装目录出发定位 SDK，正常情况下即同一实例；
   若装了多份 SDK 且版本不同，可能出现补丁打在另一份上而静默失效（此时 `/消息` 行为与未装插件一致）。
2. **`node_modules` 解析**：`qqbot-nodejs` 的 `exports` 只有 `import` 条件，`createRequire().resolve()` 在 Node 24 会抛
   `ERR_PACKAGE_PATH_NOT_EXPORTED`（实测）。本插件因此手工读 package.json 的 `exports['.']`，不依赖该 API。
3. **历史带标记**：关键词消息进历史时带 `<@appId>` 前缀。qqbot 对真 @ 消息本来就是这样记录（原生行为），
   不算退化；但如果你希望历史完全干净，需要在 `historyBuffer` 之后、`mentionGate` 之前插中间件，
   那就要按注册顺序定位，脆弱得多，本插件没有采用。
4. **版本漂移**：只用 qqbot/SDK 的四个公开面 —— `middlewares`、`handleInboundMessage`、`ctx.bot.appId`、
   `ctx.state.mention`。白名单外的版本仍会尝试安装并打 warn，出问题先跑 `selfcheck.mjs`。

## 边界

- 不改 qqbot 源码，不写 `node_modules`，不碰 App 的 Java 层。
- 媒体缓存、脚本、手册等其余增强项不在本插件范围内（另行安排）。
