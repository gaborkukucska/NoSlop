/**
 * NoSlop proxy worker: HMAC-SHA256 authenticated, allow-listed and rate-limited.
 *
 * What it does
 * - Auth: every request except /health carries X-Proxy-Timestamp and X-Proxy-Signature,
 *   HMAC-SHA256 of "<timestamp>:<payload>" with the shared secret (the URL for GET, the
 *   body for POST). The secret never crosses the wire. There is no built-in fallback
 *   secret: without PROXY_SECRET the worker refuses everything.
 * - Allow list: only the calls the app makes are forwarded (see ROUTES). Anything else is
 *   404 or 405 before any upstream request, so a leaked secret can't turn the worker into
 *   a general proxy for YouTube, Reddit or Jamendo.
 * - Rate limits: optional Workers Rate Limiting bindings, one per client address and one
 *   per route (see wrangler.toml). Without the bindings the worker still runs, unlimited.
 *   Over a limit the answer is 429 with Retry-After; the app then goes direct.
 * - No client leaks: upstream requests carry only the headers built here, never the
 *   client's address or the proxy's own headers.
 * - Caching: YouTube search and player answers by body hash, Reddit and Jamendo by URL,
 *   for a few minutes. The Cache API only works when the worker is served on a custom
 *   domain; on *.workers.dev every lookup is a MISS.
 *
 * Secrets and variables (Settings > Variables, or `wrangler secret put`):
 * - PROXY_SECRET            required. The HMAC key the app is built with.
 * - PROXY_SECRET_PREVIOUS   optional. Also accepted, so builds made with the old key keep
 *                           working while users update after a key change. Remove it once
 *                           they have.
 */

const MAX_CLOCK_SKEW_SECONDS = 600; // Tor latency and phone clock drift
const UPSTREAM_TIMEOUT_MS = 15000;
const MAX_BODY_BYTES = 16 * 1024; // an InnerTube search or player body is about 1 KB

const REDDIT_HOSTS = ['https://old.reddit.com', 'https://www.reddit.com'];
const REDDIT_DEFAULT_UA = 'android:com.noslop.app:v0.7 (by /u/NoSlopApp)';
const DEFAULT_UA = 'NoSlop-Android/1.0 (+https://github.com/gaborkukucska/NoSlop)';

/**
 * The only calls forwarded. `path` is matched against the path after the route prefix.
 * cacheSeconds 0 means never cached.
 */
const ROUTES = [
  {
    name: 'youtube',
    prefix: '/youtubei/v1',
    method: 'POST',
    path: /^\/(search|player)$/,
    cacheSeconds: 300,
  },
  {
    name: 'reddit',
    prefix: '/reddit',
    method: 'GET',
    // /r/<sub>/<sort>.json, /r/<sub>/search.json, /search.json (and .rss for older builds).
    // <sub> may be a multireddit, a+b+c.
    path: /^\/(r\/[A-Za-z0-9_+]{2,200}\/)?(hot|new|top|rising|controversial|best|search)\.(json|rss)$/,
    cacheSeconds: 120,
  },
  {
    name: 'jamendo',
    prefix: '/jamendo',
    method: 'GET',
    path: /^\/v3\.0\/tracks\/?$/,
    cacheSeconds: 300,
  },
];

function findRoute(pathname) {
  for (const route of ROUTES) {
    if (pathname === route.prefix || pathname.startsWith(route.prefix + '/')) {
      return { route, rest: pathname.slice(route.prefix.length) };
    }
  }
  return null;
}

function plain(status, text, extra = {}) {
  return new Response(text, { status, headers: { 'Content-Type': 'text/plain', ...extra } });
}

function timingSafeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function hmacHex(secret, message) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(message));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Checks the signature over "<ts>:<payload>" against the current and the previous secret. */
async function verifyProxyAuth(request, env, payload) {
  const secrets = [env.PROXY_SECRET, env.PROXY_SECRET_PREVIOUS].filter((s) => typeof s === 'string' && s.length > 0);
  if (secrets.length === 0) return { ok: false, reason: 'proxy misconfigured' };

  const timestamp = request.headers.get('X-Proxy-Timestamp');
  const rawSignature = request.headers.get('X-Proxy-Signature');
  if (!timestamp || !rawSignature) return { ok: false, reason: 'missing signature' };

  const ts = parseInt(timestamp, 10);
  if (!Number.isFinite(ts) || String(ts) !== timestamp.trim()) return { ok: false, reason: 'bad timestamp' };
  if (Math.abs(Math.floor(Date.now() / 1000) - ts) > MAX_CLOCK_SKEW_SECONDS) return { ok: false, reason: 'stale request' };

  const signature = rawSignature.trim().toLowerCase();
  for (const secret of secrets) {
    if (timingSafeEqual(signature, await hmacHex(secret, `${ts}:${payload}`))) return { ok: true };
  }
  return { ok: false, reason: 'bad signature' };
}

/**
 * Applies a rate limit binding when it is configured. Returns true when the request may go on.
 * Limits are per Cloudflare location and approximate (Cloudflare docs).
 */
async function underLimit(limiter, key) {
  if (!limiter || typeof limiter.limit !== 'function') return true;
  try {
    const { success } = await limiter.limit({ key });
    return success;
  } catch (e) {
    return true; // never fail closed on a limiter outage
  }
}

function buildUpstreamHeaders(request, userAgent, extra = {}) {
  const h = new Headers();
  h.set('User-Agent', userAgent);
  h.set('Accept', request.headers.get('Accept') || '*/*');
  h.set('Accept-Language', request.headers.get('Accept-Language') || 'en-US,en;q=0.9');
  const ct = request.headers.get('Content-Type');
  if (ct) h.set('Content-Type', ct);
  for (const [k, v] of Object.entries(extra)) h.set(k, v);
  return h;
}

function buildDownstreamHeaders(upstreamResponse, meta) {
  const h = new Headers(upstreamResponse.headers);
  for (const name of ['Content-Encoding', 'Content-Length', 'Transfer-Encoding', 'Set-Cookie', 'Content-Security-Policy']) {
    h.delete(name);
  }
  for (const [k, v] of Object.entries(meta)) h.set(k, v);
  return h;
}

async function fetchWithTimeout(request, ms = UPSTREAM_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  try {
    return await fetch(request, { signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function fetchReddit(rest, search, request) {
  // Reddit asks for a unique, descriptive User-Agent; pass the app's own when it is one.
  const appUa = request.headers.get('User-Agent') || '';
  const ua = /^android:com\.noslop\.[a-z.]+:v?[\w.\-]+ \(by \/u\/NoSlopApp\)$/.test(appUa) ? appUa : REDDIT_DEFAULT_UA;
  let last = null;
  for (let i = 0; i < REDDIT_HOSTS.length; i++) {
    const req = new Request(REDDIT_HOSTS[i] + rest + search, {
      method: 'GET',
      headers: buildUpstreamHeaders(request, ua),
      redirect: 'follow',
    });
    try {
      const res = await fetchWithTimeout(req);
      if (res.status !== 403 && res.status !== 429) return { res, upstream: REDDIT_HOSTS[i], attempt: i + 1 };
      last = { res, upstream: REDDIT_HOSTS[i], attempt: i + 1 };
    } catch (e) {
      last = null;
    }
  }
  return last || { res: plain(502, 'All Reddit hosts refused or timed out'), upstream: 'none', attempt: REDDIT_HOSTS.length };
}

async function cached(cacheKey) {
  if (!cacheKey) return null;
  try {
    return await caches.default.match(cacheKey);
  } catch (e) {
    return null;
  }
}

/** Buffers a 200 answer, stores it under cacheKey for `seconds`, and returns it. */
async function storeAndReturn(res, headers, cacheKey, seconds, ctx) {
  headers.set('Cache-Control', `public, max-age=${seconds}`);
  const buf = await res.arrayBuffer();
  const out = new Response(buf, { status: 200, headers });
  ctx.waitUntil(caches.default.put(cacheKey, out.clone()).catch(() => {}));
  return out;
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (url.pathname === '/health') {
      return new Response(
        JSON.stringify({
          ok: true,
          ts: Date.now(),
          routes: ROUTES.map((r) => r.prefix),
          configured: Boolean(env.PROXY_SECRET),
          rateLimited: Boolean(env.CLIENT_LIMITER || env.ROUTE_LIMITER),
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    }

    // 1. Only the calls the app makes.
    const match = findRoute(url.pathname);
    if (!match || !match.route.path.test(match.rest)) return plain(404, 'Not Found');
    const { route, rest } = match;
    if (request.method !== route.method) return plain(405, 'Method Not Allowed', { Allow: route.method });

    // 2. Per-client limit, before any work. Many Tor users share one exit address, so keep it generous.
    const client = request.headers.get('CF-Connecting-IP') || 'unknown';
    if (!(await underLimit(env.CLIENT_LIMITER, `${route.name}:${client}`))) {
      return plain(429, 'Too Many Requests', { 'Retry-After': '60', 'X-Proxy-Limit': 'client' });
    }

    // 3. The signed payload: the URL for GET, the body for POST (bounded).
    let body = null;
    if (route.method === 'POST') {
      const declared = parseInt(request.headers.get('Content-Length') || '0', 10);
      if (declared > MAX_BODY_BYTES) return plain(413, 'Payload Too Large');
      body = await request.text();
      if (body.length > MAX_BODY_BYTES) return plain(413, 'Payload Too Large');
    }
    const auth = await verifyProxyAuth(request, env, body ?? request.url);
    if (!auth.ok) {
      return plain(401, 'Unauthorized', { 'X-Proxy-Auth': 'reject', 'X-Proxy-Auth-Reason': auth.reason || 'unknown' });
    }

    // 4. Per-route brake, so one route can't use up the account's daily requests.
    if (!(await underLimit(env.ROUTE_LIMITER, route.name))) {
      return plain(429, 'Too Many Requests', { 'Retry-After': '60', 'X-Proxy-Limit': 'route' });
    }

    // 5. Cache.
    let cacheKey = null;
    if (route.cacheSeconds > 0) {
      const id = route.method === 'POST' ? `${rest}/${await sha256Hex(body)}` : `${rest}${url.search}`;
      cacheKey = new Request(`https://cache.noslop.invalid/${route.name}${id.startsWith('/') ? '' : '/'}${id}`, { method: 'GET' });
      const hit = await cached(cacheKey);
      if (hit) {
        const h = new Headers(hit.headers);
        h.set('X-Proxy-Cache', 'HIT');
        return new Response(hit.body, { status: hit.status, headers: h });
      }
    }

    // 6. Upstream.
    let res;
    let meta;
    try {
      if (route.name === 'reddit') {
        const r = await fetchReddit(rest, url.search, request);
        res = r.res;
        meta = { 'X-Proxy-Upstream': r.upstream, 'X-Proxy-Attempt': String(r.attempt) };
      } else if (route.name === 'youtube') {
        res = await fetchWithTimeout(
          new Request('https://www.youtube.com' + url.pathname + url.search, {
            method: 'POST',
            headers: buildUpstreamHeaders(request, request.headers.get('User-Agent') || DEFAULT_UA, {
              Origin: 'https://www.youtube.com',
              Referer: 'https://www.youtube.com/',
            }),
            body,
          })
        );
        meta = { 'X-Proxy-Upstream': 'www.youtube.com' };
      } else {
        res = await fetchWithTimeout(
          new Request('https://api.jamendo.com' + rest + url.search, {
            method: 'GET',
            headers: buildUpstreamHeaders(request, DEFAULT_UA),
          })
        );
        meta = { 'X-Proxy-Upstream': 'api.jamendo.com' };
      }
    } catch (e) {
      return plain(504, 'Upstream timeout');
    }

    const headers = buildDownstreamHeaders(res, { ...meta, 'X-Proxy-Cache': cacheKey ? 'MISS' : 'BYPASS' });
    if (cacheKey && res.status === 200) return storeAndReturn(res, headers, cacheKey, route.cacheSeconds, ctx);
    return new Response(res.body, { status: res.status, headers });
  },
};
