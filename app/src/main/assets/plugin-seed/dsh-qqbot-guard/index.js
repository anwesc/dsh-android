/**
 * qqbot-guard — dsh-android 内置的 qqbot 增强层（伴侣插件）。
 *
 * 目标：qqbot 主体仍由用户从插件市场安装，本插件只补三件事——
 *   1) 群聊关键词放行（不 @ 也能触发）；
 *   2) 关键词触发时把历史上下文带进本轮（qqbot 原逻辑只有 wasMentioned 才拼历史）；
 *   3) 全程不改 qqbot / SDK 的磁盘文件，只做进程内补丁。
 *
 * 做法（已在本仓库 tools 的探针里验证，见 plugins/qqbot-guard/README.md）：
 *   - 不抢 `bot.use` 的调用时机，改为懒注入：
 *     重写 `QQBot.prototype.handleInboundMessage`，首条入站消息时才把 guard
 *     `unshift` 到 `this.middlewares` 头部，因此**与插件加载顺序无关**。
 *   - guard 命中关键词时，在正文前加 `<@appId>`，让 mentionGate 自己判定
 *     `wasMentioned=true`，从而触发 qqbot inbound 的历史拼接；随后
 *     contentSanitizer 会把标记洗掉，模型看到的是干净文本。
 */
import { pathToFileURL } from 'node:url';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

export const name = 'qqbot-guard';
// 与 qqbot 插件保持一致的最小依赖，保证它排在 agents 之后激活即可。
export const inject = ['agents'];

const SDK_PKG = '@tencent-connect/qqbot-nodejs';
const QQBOT_PKG = '@tencent-connect/dsh-qqbot';
const SDK_MIN = [1, 0, 5];
const SDK_MAX_EXCLUSIVE = [2, 0, 0];
const PATCH_FLAG = Symbol.for('qqbot-guard.handleInboundMessage');

export const DEFAULT_CONFIG = {
  /** 关键词放行：正文包含任一词即视为对我说话（不 @ 也可） */
  triggers: ['大肥鱼', '肥鱼', '鱼鱼'],
  /** 关键词匹配是否忽略大小写 */
  ignoreCase: true,
  /** dsh-qqbot 安装目录或 package.json 路径；留空则自动探测 */
  qqbotPackage: '',
  /**
   * 外部 JSON 配置（给不便于改 cordis.patch.yml 的场景，例如手机端）。
   * 留空则依次尝试 DSH_QQBOT_GUARD_CONFIG、$DSH_HOME/qqbot-guard.json、~/.dsh/qqbot-guard.json。
   * 优先级：这里的 cordis 配置 > JSON 文件 > DEFAULT_CONFIG。
   */
  configFile: '',
  /** SDK 版本超出白名单时是否仍然安装（默认只告警） */
  strictVersion: false,
  /** 是否输出诊断日志 */
  verbose: true,
};

// ────────────────────────── 工具 ──────────────────────────

function pickLogger(ctx) {
  const l = ctx?.logger ?? console;
  return {
    info: (m) => l.info?.(m) ?? console.log(m),
    warn: (m) => l.warn?.(m) ?? console.warn(m),
    error: (m) => l.error?.(m) ?? console.error(m),
    debug: (m) => l.debug?.(m),
  };
}

// ────────────────────────── 外部配置 ──────────────────────────

/**
 * 解析外部 JSON 配置文件的路径。
 * 手机端改 cordis.patch.yml 不方便，所以留一个 JSON 后门。
 */
export function resolveConfigFile(config = DEFAULT_CONFIG) {
  const explicit = typeof config?.configFile === 'string' ? config.configFile.trim() : '';
  if (explicit) return explicit;
  const env = process.env.DSH_QQBOT_GUARD_CONFIG;
  if (env) return env;
  const home = process.env.DSH_HOME;
  if (home) return join(home, 'qqbot-guard.json');
  try {
    return join(homedir(), '.dsh', 'qqbot-guard.json');
  } catch {
    return null;
  }
}

/** 读取外部 JSON 配置；文件不存在或内容损坏时返回 null，绝不抛出。 */
export function readConfigFile(config = DEFAULT_CONFIG, logger = console) {
  const file = resolveConfigFile(config);
  if (!file || !existsSync(file)) return null;
  try {
    const data = JSON.parse(readFileSync(file, 'utf8'));
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
      throw new Error('顶层必须是 JSON 对象');
    }
    logger.debug?.(`[qqbot-guard] 已读取外部配置 ${file}`);
    return data;
  } catch (err) {
    logger.warn?.(`[qqbot-guard] 读取配置 ${file} 失败（忽略）: ${err?.message ?? err}`);
    return null;
  }
}

/** 合并优先级：DEFAULT_CONFIG < 外部 JSON < cordis 里的 config。 */
export function mergeConfig(config = {}, logger = console) {
  const explicit = config ?? {};
  const file = readConfigFile(explicit, logger) ?? {};
  return { ...DEFAULT_CONFIG, ...file, ...explicit };
}

function parseVersion(v) {  const m = String(v ?? '').trim().match(/^(\d+)\.(\d+)\.(\d+)/);
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

function cmp(a, b) {
  for (let i = 0; i < 3; i += 1) {
    if (a[i] !== b[i]) return a[i] - b[i];
  }
  return 0;
}

function readPkg(dir) {
  const file = existsSync(join(dir, 'package.json')) ? join(dir, 'package.json') : dir;
  if (!existsSync(file)) return null;
  try {
    const pkg = JSON.parse(readFileSync(file, 'utf8'));
    return { pkg, dir: dirname(file), file };
  } catch {
    return null;
  }
}

/** 探测 dsh-qqbot 的安装目录（也就是 qqbot 插件本体）。 */
export function findQqbotPackage(config = DEFAULT_CONFIG, logger = console) {
  const candidates = [];
  const push = (p) => {
    if (p && typeof p === 'string') candidates.push(p);
  };
  push(config.qqbotPackage);
  push(process.env.DSH_QQBOT_DIR);

  const home = process.env.DSH_HOME;
  if (home) {
    push(join(home, 'node_modules', QQBOT_PKG));
    // 插件市场常见的两种落盘布局
    push(join(home, 'plugins', 'node_modules', QQBOT_PKG));
    const pluginRoot = join(home, 'plugins');
    if (existsSync(pluginRoot)) {
      try {
        for (const entry of readdirSync(pluginRoot, { withFileTypes: true })) {
          if (!entry.isDirectory()) continue;
          push(join(pluginRoot, entry.name, 'node_modules', QQBOT_PKG));
        }
      } catch {
        /* 读不到就跳过 */
      }
    }
  }
  push(join(process.cwd(), 'node_modules', QQBOT_PKG));

  for (const c of candidates) {
    const hit = readPkg(c);
    if (hit?.pkg?.name === QQBOT_PKG) return hit;
  }
  logger.warn?.(
    `[qqbot-guard] 没找到 ${QQBOT_PKG}；请用 config.qqbotPackage 或环境变量 DSH_QQBOT_DIR 指向它的安装目录`,
  );
  return null;
}

/**
 * 定位 SDK 的真实文件，保证和 qqbot 拿到的是同一个模块实例。
 *
 * 不能用 createRequire(...).resolve：qqbot-nodejs 的 exports 只有 "import"，
 * CJS 解析器会直接抛 ERR_PACKAGE_PATH_NOT_EXPORTED（实测 Node 24）。
 * 这里按 Node 的 node_modules 向上查找规则手工定位，再读它自己的 exports 映射。
 */
export function resolveSdk(qqbotDir) {
  const segments = SDK_PKG.split('/');
  let dir = qqbotDir;
  let pkgHit = null;
  while (dir && dir !== dirname(dir)) {
    const candidate = join(dir, 'node_modules', ...segments);
    const hit = readPkg(candidate);
    if (hit?.pkg?.name === SDK_PKG) {
      pkgHit = hit;
      break;
    }
    dir = dirname(dir);
  }
  if (!pkgHit) {
    throw new Error(`从 ${qqbotDir} 向上找不到 ${SDK_PKG} 的安装位置`);
  }
  const exp = pkgHit.pkg.exports?.['.'];
  const target =
    (typeof exp === 'string' ? exp : exp?.import ?? exp?.default ?? exp?.require) ??
    pkgHit.pkg.module ??
    pkgHit.pkg.main ??
    './dist/index.js';
  const entry = join(pkgHit.dir, target);
  if (!existsSync(entry)) {
    throw new Error(`${SDK_PKG} 入口不存在：${entry}`);
  }
  return { entry, pkgFile: pkgHit.file, version: pkgHit.pkg.version, dir: pkgHit.dir };
}

// ────────────────────────── guard 中间件 ──────────────────────────

/**
 * 真正的门禁逻辑。挂在中间件链头部，只做一件事：
 * 命中关键词且正文里没有 `<@appId>` 时，前置标记，让下游 mentionGate 认账。
 */
export function createGuard(config = DEFAULT_CONFIG, logger = console) {
  const triggers = (config.triggers ?? []).filter((t) => typeof t === 'string' && t.length > 0);
  const ignoreCase = config.ignoreCase !== false;

  return async function qqbotGuard(ctx, next) {
    try {
      const msg = ctx?.message;
      // 只处理群聊；私聊本来就 alwaysAnswer
      if (msg?.kind === 'group' && triggers.length > 0) {
        const raw = typeof msg.content === 'string' ? msg.content : '';
        const appId = ctx?.bot?.appId;
        if (raw && appId) {
          const tagged = raw.includes(`<@${appId}>`) || raw.includes(`<@!${appId}>`);
          if (!tagged) {
            const hay = ignoreCase ? raw.toLowerCase() : raw;
            const hit = triggers.find((t) =>
              hay.includes(ignoreCase ? t.toLowerCase() : t),
            );
            if (hit) {
              msg.content = `<@${appId}> ${raw}`;
              ctx.state = ctx.state ?? {};
              ctx.state.qqbotGuard = { trigger: hit };
              logger.debug?.(`[qqbot-guard] keyword "${hit}" → 标记为 @，放行`);
            }
          }
        }
      }
    } catch (err) {
      // 门禁本身绝不把消息链路带崩
      logger.error?.(`[qqbot-guard] guard 异常（已忽略）: ${err?.message ?? err}`);
    }
    await next();
  };
}

// ────────────────────────── 补丁安装 ──────────────────────────

/**
 * 给 QQBot 类打懒注入补丁。
 * 幂等：重复调用（含 HMR 重载）只装一次。
 * @returns {{ installed: boolean, restored?: () => void, reason?: string }}
 */
export function installGuard(QQBot, guard, logger = console) {
  if (typeof QQBot?.prototype?.handleInboundMessage !== 'function') {
    return { installed: false, reason: 'QQBot.prototype.handleInboundMessage 不存在，版本可能已变' };
  }
  const current = QQBot.prototype.handleInboundMessage;
  if (current?.[PATCH_FLAG]) {
    current.guard = guard; // 热重载时换用新的 guard 实例
    return { installed: true, reason: 'already-patched' };
  }

  const original = current;
  const patched = function handleInboundMessage(raw, ...rest) {
    try {
      if (!this.__qqbotGuardInstalled) {
        if (Array.isArray(this.middlewares)) {
          this.middlewares.unshift(patched.guard);
          this.__qqbotGuardInstalled = true;
          logger.debug?.('[qqbot-guard] 已挂载到中间件链头部');
        }
      }
    } catch (err) {
      logger.error?.(`[qqbot-guard] 挂载失败: ${err?.message ?? err}`);
    }
    return original.call(this, raw, ...rest);
  };
  patched[PATCH_FLAG] = true;
  patched.guard = guard;
  patched.original = original;

  QQBot.prototype.handleInboundMessage = patched;
  return {
    installed: true,
    restored: () => {
      if (QQBot.prototype.handleInboundMessage === patched) {
        QQBot.prototype.handleInboundMessage = original;
      }
    },
  };
}

// ────────────────────────── cordis 插件入口 ──────────────────────────

export async function apply(ctx, config) {
  const log = pickLogger(ctx);
  const cfg = mergeConfig(config, log);

  const qqbot = findQqbotPackage(cfg, log);
  if (!qqbot) return;

  let sdkInfo;
  try {
    sdkInfo = resolveSdk(qqbot.dir);
  } catch (err) {
    log.error(`[qqbot-guard] 解析 ${SDK_PKG} 失败: ${err?.message ?? err}`);
    return;
  }

  const sdkVersion = parseVersion(sdkInfo.version);
  const supported =
    sdkVersion && cmp(sdkVersion, SDK_MIN) >= 0 && cmp(sdkVersion, SDK_MAX_EXCLUSIVE) < 0;
  if (!supported) {
    const msg = `[qqbot-guard] ${SDK_PKG} 版本 ${sdkInfo.version ?? '未知'} 不在白名单 ${SDK_MIN.join('.')}~<${SDK_MAX_EXCLUSIVE.join('.')}`;
    if (cfg.strictVersion) {
      log.error(`${msg}；strictVersion=true，放弃安装`);
      return;
    }
    log.warn(`${msg}；仍尝试安装（内部只用公开字段 middlewares / handleInboundMessage，多半可用）`);
  }

  let sdk;
  try {
    sdk = await import(pathToFileURL(sdkInfo.entry).href);
  } catch (err) {
    // 退回按包名解析，尽量拿同一个实例（可能因 realpath 不同而失效）
    try {
      sdk = await import(SDK_PKG);
      log.warn(`[qqbot-guard] 按路径导入失败，已回退到裸包名导入：${err?.message ?? err}`);
    } catch (err2) {
      log.error(`[qqbot-guard] 导入 ${SDK_PKG} 失败: ${err2?.message ?? err2}`);
      return;
    }
  }

  const guard = createGuard(cfg, log);
  const result = installGuard(sdk.QQBot, guard, log);
  if (!result.installed) {
    log.error(`[qqbot-guard] 补丁未安装：${result.reason}`);
    return;
  }

  if (cfg.verbose) {
    const cfgFile = resolveConfigFile(cfg);
    log.info(
      `[qqbot-guard] 就绪：qqbot=${qqbot.pkg?.version ?? '?'} sdk=${sdkInfo.version ?? '?'} ` +
        `triggers=${JSON.stringify(cfg.triggers)} 配置源=${existsSync(cfgFile ?? '') ? cfgFile : 'cordis/默认'} ` +
        `注入方式=lazy-unshift（与加载顺序无关）`,
    );
  }

  if (result.restored && typeof ctx?.on === 'function') {
    ctx.on('dispose', () => {
      result.restored();
      log.info('[qqbot-guard] 已卸载补丁');
    });
  }
}
