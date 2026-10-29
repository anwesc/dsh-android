/**
 * qqbot-guard 自检：在真实 qqbot 中间件链上验证懒注入补丁是否生效。
 *
 * 用法（在装有 dsh-qqbot 的机器上）：
 *   DSH_QQBOT_DIR=/path/to/node_modules/@tencent-connect/dsh-qqbot node selfcheck.mjs
 *   node selfcheck.mjs --qqbot /path/to/dsh-qqbot
 *
 * 退出码：0 = 全部通过；1 = 有断言失败；2 = 找不到 qqbot / SDK。
 */
import { pathToFileURL } from 'node:url';
import { join } from 'node:path';
import {
  DEFAULT_CONFIG,
  findQqbotPackage,
  resolveSdk,
  installGuard,
  createGuard,
} from './index.js';

const argvDir = (() => {
  const i = process.argv.indexOf('--qqbot');
  return i > -1 ? process.argv[i + 1] : '';
})();

const logger = { info() {}, warn() {}, error() {}, debug() {}, log() {} };
const qqbot = findQqbotPackage({ ...DEFAULT_CONFIG, qqbotPackage: argvDir }, logger);
if (!qqbot) {
  console.error('FAIL: 没找到 @tencent-connect/dsh-qqbot，请用 --qqbot 或 DSH_QQBOT_DIR 指定');
  process.exit(2);
}

const sdkInfo = resolveSdk(qqbot.dir);
const sdk = await import(pathToFileURL(sdkInfo.entry).href);

if (typeof sdk.QQBot?.prototype?.handleInboundMessage !== 'function') {
  console.error('FAIL: SDK 里没有 QQBot.prototype.handleInboundMessage');
  process.exit(2);
}

const setupMod = await import(
  pathToFileURL(join(qqbot.dir, 'dist', 'gateway', 'middleware-setup.js')).href
);
const storeMod = await import(
  pathToFileURL(join(qqbot.dir, 'dist', 'features', 'history-store.js')).href
);

const APP = '102000000';
const KEYWORD = 'YONGJIE';

// 先按 qqbot 原样建 bot（补丁前），验证补丁不依赖加载顺序
const bot = new sdk.QQBot({ appId: APP, appSecret: 'secret', transport: 'websocket', logger });
const config = {
  appId: APP,
  requireMention: true,
  historyLimit: 10,
  debug: false,
  textChunkLimit: 4500,
  access: { c2cMode: 'open', c2cAllow: [], groupMode: 'open', groupAllow: [] },
};
setupMod.setupMiddlewares(bot, config, {}, logger);

const chainLenBefore = bot.getMiddlewares().length;
const guard = createGuard({ ...DEFAULT_CONFIG, triggers: [KEYWORD] }, logger);
const patch = installGuard(sdk.QQBot, guard, logger);

const seen = [];
const decisions = [];
bot.on('message', (ctx) => {
  seen.push(String(ctx.message.content ?? ''));
  decisions.push(ctx.state.mention ?? null);
});

const raw = (id, content) => ({
  kind: 'group',
  groupOpenid: 'G1',
  senderId: 'U1',
  senderName: 'A',
  messageId: id,
  content,
  timestamp: new Date().toISOString(),
  attachments: [],
  rawEventType: 'GROUP_MESSAGE_CREATE',
});

await bot.handleInboundMessage(raw('M1', 'casual chat no trigger'));
await bot.handleInboundMessage(raw('M2', `${KEYWORD} how to`));
await bot.handleInboundMessage(raw('M3', `<@${APP}> hello there`));

const store = storeMod.getHistoryStore();
const key = storeMod.historyGroupKey(APP, 'G1');
const history = store.list(key, 20).map((e) => String(e.content));

const results = [
  ['补丁已安装', patch.installed === true],
  ['链头部是 guard', bot.getMiddlewares()[0] === guard],
  ['补丁未改变原链长度（懒注入时机正确）', bot.getMiddlewares().length === chainLenBefore + 1],
  // 用 includes 而不是 startsWith：现场安装的 qqbot 可能还叠了别的门禁中间件，
  // 会在正文前加前缀（例如「[门禁] 本条来自非管理员…」），正文不再是关键词开头。
  ['关键词消息被处理', seen.some((c) => c.includes(KEYWORD))],
  ['关键词消息 wasMentioned=true', decisions[1]?.wasMentioned === true],
  ['模型收到的是干净文本（无 <@ 标记）', !seen.some((c) => c.includes('<@'))],
  ['无关键词的消息被门控拦下', seen.length === 2],
  ['群历史已累积', history.length >= 2],
];

console.log(`qqbot=${qqbot.pkg.version} sdk=${sdkInfo.version ?? '?'} chain=${chainLenBefore}`);
for (const [label, ok] of results) console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}`);

const failed = results.filter(([, ok]) => !ok).length;
console.log(failed === 0 ? '\n全部通过' : `\n${failed} 项失败`);
process.exit(failed === 0 ? 0 : 1);
