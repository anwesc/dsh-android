// Resolution cache for the harness boot.
//
// Under proot every module lookup costs a ptrace round trip, and dsh's startup spends most of
// its ~20 s resolving the same ~12k (specifier, parent) pairs on every cold start. This hook
// records them once and replays the answer afterwards, which removes the stat/realpath walk.
//
// Only paths inside the image (/opt) are cached: the image is immutable until the runtime
// installer replaces it (and clears this cache), while profile plugins under /root/.dsh are
// user data that can appear or disappear at any time.
import fs from 'node:fs';
import { registerHooks } from 'node:module';

const CACHE = process.env.DSH_RESOLVE_CACHE || '/root/.dshboot/res-cache.jsonl';
const map = new Map();
let hits = 0;
let misses = 0;
let pending = [];

try {
  for (const line of fs.readFileSync(CACHE, 'utf8').split('\n')) {
    if (!line) continue;
    try {
      const [key, url] = JSON.parse(line);
      map.set(key, url);
    } catch {
      // a torn line from an interrupted boot is not worth failing over
    }
  }
} catch {
  // first boot: the cache is built as modules resolve
}

const flush = () => {
  if (pending.length === 0) return;
  const chunk = pending.join('');
  pending = [];
  try {
    fs.appendFileSync(CACHE, chunk);
  } catch {
    // a read-only cache only costs speed
  }
};

registerHooks({
  resolve(specifier, context, nextResolve) {
    const key = specifier + '\u0000' + (context.parentURL || '');
    const hit = map.get(key);
    if (hit !== undefined) {
      hits++;
      return { url: hit, shortCircuit: true };
    }
    misses++;
    const result = nextResolve(specifier, context);
    if (typeof result.url === 'string' && result.url.startsWith('file:///opt/')) {
      map.set(key, result.url);
      pending.push(JSON.stringify([key, result.url]) + '\n');
    }
    return result;
  },
});

const timer = setInterval(flush, 2000);
timer.unref();

setTimeout(() => {
  flush();
  fs.writeSync(2, 'res-cache: hits=' + hits + ' misses=' + misses + ' entries=' + map.size + '\n');
}, 20000);
