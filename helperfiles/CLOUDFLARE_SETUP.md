# Cloudflare Tunnel Configuration Guide

## Quick Fix for Your Current Setup

The deployer has been fixed to fully support **Hybrid Access** (Simultaneous Local + Web)!

### 1. Update Your `.env` File

Edit `/home/tom/NoSlop/.env` and make sure these are set:

```bash
# Set external URLs to your Cloudflare domain
NOSLOP_FRONTEND_EXTERNAL_URL=https://app.noslop.me
NOSLOP_BACKEND_EXTERNAL_URL=https://app.noslop.me
```

### 2. Redeploy the Framework

```bash
cd /home/tom/NoSlop
python -m seed.seed_cli deploy
```

### 3. Verify Cloudflare Tunnel Configuration

Make sure your Cloudflare Tunnel points to:
*   **Public Hostname**: `app.noslop.me` (or your domain)
*   **Service**: `http://localhost:3000` (Direct to Frontend) or `http://localhost:8080` (Direct to Caddy)
*   *Note: Both work now! Pointing to port 3000 is supported via our new rewrite rules.*

### 4. Test Access

**Web Access (HTTPS)**:
*   Visit `https://app.noslop.me`
*   ✅ No Mixed Content errors
*   ✅ Health check passes (via `/health` rewrite)
*   ✅ Chat works

**Local Access (HTTP)**:
*   Visit `http://192.168.0.22:3000` (Your Node IP)
*   ✅ Login works (no connection refused)
*   ✅ Backend connected

## How It Works

**Before (Broken)**:
- Frontend: `https://app.noslop.me`
- Tries to call: `http://app.noslop.me:8000/health` ❌ (blocked by browser)
- WebSocket: `ws://localhost:8000/ws/activity` ❌ (wrong host)

**After (Fixed)**:
- Frontend: `https://app.noslop.me`
- API calls: `https://app.noslop.me/api/health` ✅
- WebSocket: `wss://app.noslop.me/ws/activity` ✅
- Caddy routes `/api/*` and `/ws/*` to backend internally

## Troubleshooting

### If you still see errors:

1. **Check Caddy is running**:
   ```bash
   sudo systemctl status caddy
   ```

2. **View Caddy logs**:
   ```bash
   sudo tail -f /var/log/caddy/access.log
   ```

3. **Test backend directly**:
   ```bash
   curl http://localhost:8000/health
   ```

4. **Restart all services**:
   ```bash
   sudo systemctl restart caddy noslop-backend noslop-frontend
   ```
