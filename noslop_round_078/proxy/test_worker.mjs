// Checks the proxy Worker against stubbed fetch, cache and rate limiters.
// Run with: node proxy/test_worker.mjs   (Node 18 or later). Exits 1 on any failure.
import worker from './worker.js';
import { createHmac } from 'node:crypto';

const SECRET = 'current-secret';
const PREVIOUS = 'previous-secret';
const BASE = 'https://proxy.example';

const upstream = [];
const store = new Map();
globalThis.caches = {
  default: {
    match: async (k) => store.get(k.url)?.clone(),
    put: async (k, r) => { store.set(k.url, r); },
  },
};
globalThis.fetch = async (req) => {
  upstream.push(req.url);
  return new Response('{"ok":1}', { status: 200, headers: { 'Set-Cookie': 'tracking=1', 'Content-Type': 'application/json' } });
};
const ctx = { waitUntil: (p) => p };

const limiter = (max) => {
  const counts = new Map();
  return { limit: async ({ key }) => { const n = (counts.get(key) || 0) + 1; counts.set(key, n); return { success: n <= max }; } };
};
const env = () => ({ PROXY_SECRET: SECRET, PROXY_SECRET_PREVIOUS: PREVIOUS, CLIENT_LIMITER: limiter(3), ROUTE_LIMITER: limiter(100) });

const sign = (payload, secret = SECRET, ts = Math.floor(Date.now() / 1000)) => ({
  'X-Proxy-Timestamp': String(ts),
  'X-Proxy-Signature': createHmac('sha256', secret).update(`${ts}:${payload}`).digest('hex'),
});

let failures = 0;
async function expect(name, request, status, check = () => true, e = shared) {
  const res = await worker.fetch(request, e, ctx);
  const ok = res.status === status && check(res);
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}: ${res.status}${ok ? '' : ` (expected ${status})`}`);
  return res;
}

const shared = env();
const ip = (n) => ({ 'CF-Connecting-IP': `198.51.100.${n}` });

const reddit = `${BASE}/reddit/r/technology/hot.json?limit=25&raw_json=1`;
await expect('Reddit listing, signed', new Request(reddit, { headers: { ...sign(reddit), ...ip(1) } }), 200,
  (r) => r.headers.get('X-Proxy-Cache') === 'MISS' && !r.headers.get('Set-Cookie'));
await expect('Reddit listing again is served from the cache', new Request(reddit, { headers: { ...sign(reddit), ...ip(2) } }), 200,
  (r) => r.headers.get('X-Proxy-Cache') === 'HIT');
const search = `${BASE}/reddit/search.json?q=privacy&sort=new&limit=25&raw_json=1`;
await expect('Reddit search, signed with the previous secret', new Request(search, { headers: { ...sign(search, PREVIOUS), ...ip(3) } }), 200);
const multi = `${BASE}/reddit/r/privacy+netsec/new.json`;
await expect('Multireddit', new Request(multi, { headers: { ...sign(multi), ...ip(4) } }), 200);
await expect('Wrong secret', new Request(reddit, { headers: { ...sign(reddit, 'wrong'), ...ip(5) } }), 401,
  (r) => r.headers.get('X-Proxy-Auth-Reason') === 'bad signature');
await expect('Stale timestamp', new Request(reddit, { headers: { ...sign(reddit, SECRET, 1000), ...ip(6) } }), 401,
  (r) => r.headers.get('X-Proxy-Auth-Reason') === 'stale request');
await expect('Unsigned', new Request(reddit, { headers: ip(7) }), 401);
const other = `${BASE}/reddit/api/v1/me.json`;
await expect('Reddit path the app never uses', new Request(other, { headers: sign(other) }), 404);
await expect('Reddit POST', new Request(reddit, { method: 'POST', body: 'x', headers: sign('x') }), 405);

const body = JSON.stringify({ context: { client: { clientName: 'WEB' } }, query: 'cats' });
const yt = `${BASE}/youtubei/v1/search?key=k&prettyPrint=false`;
await expect('YouTube search, body signed', new Request(yt, { method: 'POST', body, headers: { ...sign(body), 'Content-Type': 'application/json', ...ip(8) } }), 200);
await expect('YouTube browse is not forwarded', new Request(`${BASE}/youtubei/v1/browse`, { method: 'POST', body, headers: sign(body) }), 404);
await expect('YouTube GET', new Request(yt, { headers: sign(yt) }), 405);
const big = 'x'.repeat(20000);
await expect('Oversized body', new Request(yt, { method: 'POST', body: big, headers: { ...sign(big), ...ip(9) } }), 413);

const jamendo = `${BASE}/jamendo/v3.0/tracks/?client_id=c&format=json&search=jazz`;
await expect('Jamendo tracks', new Request(jamendo, { headers: { ...sign(jamendo), ...ip(10) } }), 200);
const jamendoOther = `${BASE}/jamendo/v3.0/users/`;
await expect('Jamendo path the app never uses', new Request(jamendoOther, { headers: sign(jamendoOther) }), 404);

for (let i = 1; i <= 3; i++) {
  await expect(`Client within its limit (${i})`, new Request(jamendo, { headers: { ...sign(jamendo), ...ip(11) } }), 200);
}
await expect('Client over its limit', new Request(jamendo, { headers: { ...sign(jamendo), ...ip(11) } }), 429,
  (r) => r.headers.get('Retry-After') === '60');
await expect('No secret configured: refuses, no built-in fallback', new Request(jamendo, { headers: sign(jamendo) }), 401,
  (r) => r.headers.get('X-Proxy-Auth-Reason') === 'proxy misconfigured', {});
await expect('Runs without rate limit bindings', new Request(search, { headers: sign(search) }), 200, () => true,
  { PROXY_SECRET: SECRET });
await expect('Health', new Request(`${BASE}/health`), 200);

const expectedUpstream = [
  'https://old.reddit.com/r/technology/hot.json?limit=25&raw_json=1',
  'https://old.reddit.com/search.json?q=privacy&sort=new&limit=25&raw_json=1',
  'https://old.reddit.com/r/privacy+netsec/new.json',
  'https://www.youtube.com/youtubei/v1/search?key=k&prettyPrint=false',
  'https://api.jamendo.com/v3.0/tracks/?client_id=c&format=json&search=jazz',
];
const seen = [...new Set(upstream)];
const upstreamOk = JSON.stringify(seen) === JSON.stringify(expectedUpstream);
if (!upstreamOk) failures++;
console.log(`${upstreamOk ? 'PASS' : 'FAIL'}  only the expected upstream URLs were called${upstreamOk ? '' : `: ${JSON.stringify(seen)}`}`);

console.log(failures === 0 ? '\nAll checks passed.' : `\n${failures} check(s) failed.`);
process.exit(failures === 0 ? 0 : 1);
