# 推荐插件清单

| 插件 | 用途 |
|---|---|
| `dsh-plugin` | 插件市场，图形化浏览/安装社区插件 |
| `dsh-context` | 上下文管理，长会话不丢信息 |
| `@tencent-connect/dsh-qqbot` | QQ 通道，手机/群里直接对话 |
| `deepseek-harness-humanity` | 人格与长期记忆 |
| `dsh-bili-scroll` | 刷 B 站信息流 |
| `dsh-xiaomi-tts` | 小米语音合成，语音播报 |
| `dsh-remotion` | Remotion 视频制作 |

安装（任选其一）：

```bash
dsh plugin --profile web add <包名>
curl -s -X POST http://127.0.0.1:3081/reload
```

或在 App 里：设置 → 插件 → 添加插件。

## 随 App 内置：qqbot-guard

装了 `@tencent-connect/dsh-qqbot` 之后，群聊默认要求 @ 机器人才回复。App 自带一个补丁插件
`dsh-qqbot-guard`，让群里出现关键词时也能触发对话（相当于帮你补一个 @）。

- 关键词写在 `/root/.dsh/qqbot-guard.json`，首次启动自动生成：

```json
{
  "triggers": ["大肥鱼", "肥鱼", "鱼鱼"],
  "_note": "群聊里不 @ 也能触发回复的关键词；改完重启 App 生效。"
}
```

- 改完关键词重启 App（或 POST `/reload`）即可，不用碰 YAML。
- 想临时关掉：把这个 JSON 里的 `triggers` 改成 `[]`，或者在 profile 的 `cordis.patch.yml`
  里给同 id 的 `qqbot-guard` 写 `triggers: []`。
- 插件在每次启动时重新落盘，所以升级 App 或回滚配置都不会把它弄丢。
