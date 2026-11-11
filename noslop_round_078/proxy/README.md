# NoSlop proxy Worker

YouTube, Reddit and Jamendo block many Tor exits. The app sends three kinds of request
through this Cloudflare Worker so they still work over Tor:

| Route | Method | What the app asks for |
|---|---|---|
| `/youtubei/v1/search` (and `/player`) | POST | YouTube search, through the InnerTube interface |
| `/reddit/r/<sub>/<sort>.json`, `/reddit/search.json` | GET | Subreddit listings and Reddit search |
| `/jamendo/v3.0/tracks/` | GET | Jamendo track search |

Nothing else is forwarded. Video and audio bytes never go through the Worker; the app
streams them from the source.

## What the Worker can see

The search terms and subreddit names in these requests, which come from your interests.
Over Tor (the app's default) it does not see your IP address. It strips Set-Cookie and
never forwards the client's own headers upstream. Cloudflare, which runs the Worker,
can see the same requests.

## Protection

* **HMAC signature.** Every request carries `X-Proxy-Timestamp` and `X-Proxy-Signature`,
  HMAC-SHA256 of `<timestamp>:<payload>` (the URL for GET, the body for POST). Requests
  more than 10 minutes old are refused. The key ships inside the APK, so treat it as a
  speed bump, not a secret: the limits below are what stop abuse.
* **Allow list.** Any other path or method is 404 or 405, so a leaked key can't use the
  Worker as a general proxy.
* **Rate limits.** `CLIENT_LIMITER` per client address and route, `ROUTE_LIMITER` per
  route (see `wrangler.toml`). Limits are approximate and per Cloudflare location. Over a
  limit the Worker answers 429, and the app falls back to a direct request.
* **Body size.** POST bodies over 16 KB are refused.

## Run your own

```bash
cd proxy
npx wrangler@latest deploy                 # needs Wrangler 4.36.0 or later
npx wrangler secret put PROXY_SECRET       # the key your app build uses
```

Build the app with `NOSLOP_PROXY_URL` and `NOSLOP_PROXY_SECRET` set (see `docs/BUILD.md`),
or set the URL and secret in the app under Settings.

To change the key without breaking installed builds, set the new one as `PROXY_SECRET`
and the old one as `PROXY_SECRET_PREVIOUS`, ship a build with the new key, and remove
`PROXY_SECRET_PREVIOUS` once users have updated.

On the Workers Free plan an account has 100,000 requests a day. Each install refreshes
its feed many times a day, so a few hundred active installs can use that up. The app
keeps working when the Worker refuses (it goes direct), but YouTube and Reddit over Tor
work less often then.

The Cache API only works when the Worker has a custom domain. On `*.workers.dev`
every response has `X-Proxy-Cache: MISS`.

## Test

```bash
node proxy/test_worker.mjs
```

Runs the Worker against stubbed `fetch`, cache and limiters (Node 18 or later) and
exits non-zero if any check fails.
