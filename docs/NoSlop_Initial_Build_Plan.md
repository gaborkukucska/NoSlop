# NoSlop Android App — LLM Build Plan v2.0
<!-- This document is addressed directly to the LLM executing the build. -->
<!-- PRIME DIRECTIVE: Every deliverable is FUNCTIONAL, COMPLETE, COMMENTED, and GITHUB-READY. -->
<!-- No mocks. No placeholders. No "TODO: implement". No Google/Gemini. Ship it working. -->

---

## 0. Mission Briefing

You are building **NoSlop** — an Android app that is simultaneously:
1. A **personal feed aggregator** (RSS/Atom/JSON/YouTube/Reddit/Mastodon, chronological, no algorithm)
2. A **serverless social node** on the HAI-Net mesh (Tor-routed, Ed25519 identity, gossip-propagated posts, E2EE DMs)
3. The **on-ramp wedge** for the broader HAI-Net ecosystem

The app is a direct port and evolution of **gChat** (a React/Electron/Node.js R&D project that proved out
the mesh protocol). You have full access to gChat's battle-tested implementations of:
- `cryptoService.ts` — Ed25519 signing, NaCl box encryption, SHA3 tripcode derivation, AES-GCM backup
- `useNetworkLayer.ts` / `useGossipProtocol.ts` — gossip mesh, inventory sync, daisy-chain, rate limiting
- `useDiscovery.ts` — peer discovery, handshake protocol, trusted contact management
- Full packet firewall: drop ALL packets from non-trusted senders except `CONNECTION_REQUEST` / `USER_HANDSHAKE`
- Dual-agent transport: separate Control Agent (short timeout) vs Data Agent (keep-alive, long timeout) for Tor

Study these before writing any networking or crypto code. Adapt, don't reinvent.

**Stack**: Expo (React Native) · TypeScript strict · NativeWind · Zustand · expo-sqlite · @noble/curves · Expo Router

---

## 1. Non-Negotiable Rules

These apply to every single line of code, every file, every session — no exceptions:

### 1.1 Code Quality
1. **No mocks, no placeholders, no simulated data.** If a function is in scope for this phase, implement
   it fully. If it is out of scope, do not create the file at all. The project is not done until ALL
   code is functional. Never claim a task is finished if any part of it is stubbed out.
2. **TypeScript strict mode throughout.** No `any` unless wrapping a library boundary — add a comment
   explaining why. No `@ts-ignore`. Run `tsc --noEmit` before declaring a file complete.
3. **Inline comments on every non-obvious decision.** Architecture choices, crypto parameter rationale,
   Tor quirks, protocol compatibility notes — explain them in the code, not just in docs.
4. **All social layer traffic through Tor SOCKS5 proxy only.** Feed layer traffic is clearnet.
   Never route social packets over clearnet even as a fallback — fail visibly instead.
5. **No analytics, no telemetry, no crash reporting to third parties.** Local debug log only.
6. **NO Google products or Gemini API** — do not include, import, or reference any Google SDK,
   Firebase, Google Analytics, Google Play Services APIs, Gemini, or any Google-owned service
   unless the user explicitly requests it in a future session.
7. **Crypto via `@noble/curves` + `@noble/ciphers` only.** No native modules. No WebCrypto
   (not reliable in React Native). No tweetnacl. Use noble for everything.
8. **Storage via `expo-sqlite` only.** No localStorage, no AsyncStorage for persistent data,
   no in-memory-only state for anything that must survive app restart.
9. **Debug logging must be accessible at all times.** Every module logs to a structured in-memory
   ring buffer (last 500 entries) AND writes to `expo-file-system`. The Settings screen must have
   a "Copy Debug Log" button that copies the full log to clipboard. This is the primary tool
   for rapid LLM-assisted iteration.

### 1.2 GitHub Readiness (Every Session, Every Deliverable)
Every file you produce must be ready to commit to GitHub. This means every session must produce or
maintain these root-level files — they are never optional:

- **`README.md`** — Keep updated. Must always reflect current actual state of the project,
  not aspirational state. Include: what it does, what works NOW, how to build, how to run.
- **`LICENSE.md`** — GNU Affero General Public License v3.0 (AGPL-3.0), full text.
  NoSlop is free and open source software, forever.
- **`.gitignore`** — Comprehensive: node_modules, .expo, build outputs, eas.json credentials,
  .env files, iOS/Android native build artifacts, OS files (.DS_Store, Thumbs.db).
- **`docs/PROJECT_STATUS.md`** — Updated every session. Must reflect ACTUAL current state:
  what is complete and functional, what is in progress, what is planned, known issues.
  Never list something as complete if any part of it is a stub or placeholder.

### 1.3 Documentation Standards
- Every `lib/` module must have a JSDoc block at the top of the file: purpose, dependencies,
  key exports, any known limitations.
- Every exported function must have a JSDoc comment: params, return type, throws, side effects.
- Every component must document its props interface with JSDoc.
- `docs/BUILD.md` and `docs/DEBUG.md` are required deliverables (see §9).

---

## 2. Design System Constants

Apply via NativeWind custom config in `tailwind.config.js` — never hardcode hex values inline.

```
primary:   #0A0A0A   (near black — background)
accent:    #00FF88   (electric green — interactive / signal)
surface:   #141414   (card background)
border:    #2A2A2A   (subtle border)
text:      #F0F0F0   (primary text)
muted:     #666666   (secondary / timestamp text)
danger:    #FF4444   (error / destructive)
```

Fonts:
- **JetBrains Mono** — all crypto/identity elements: public keys, tripcodes, onion addresses, hashes
- **Sora** — all body text, UI labels, navigation

---

## 3. Repo Structure

Create exactly this structure. Any deviation must be documented with a reason in `docs/PROJECT_STATUS.md`.

```
noslop-app/
├── app/                              # Expo Router — screens only, thin wrappers
│   ├── onboarding/
│   │   ├── _layout.tsx               # Onboarding navigator (locks out main tabs until complete)
│   │   ├── step1-identity.tsx        # Create identity: choose handle → generate keypair → show result
│   │   ├── step2-feeds.tsx           # Pick feed categories from ready-made library
│   │   └── step3-connection.tsx      # Tutorial: how to add a connection via QR scan
│   ├── (tabs)/
│   │   ├── feed/
│   │   │   └── index.tsx             # Unified feed (RSS + mesh posts)
│   │   ├── social/
│   │   │   └── index.tsx             # Mesh post compose + gossip feed
│   │   ├── messages/
│   │   │   ├── index.tsx             # DM conversation list
│   │   │   └── [peerId].tsx          # DM thread
│   │   ├── profile/
│   │   │   └── index.tsx             # Identity, QR, key display
│   │   └── settings/
│   │       └── index.tsx             # Sources, filters, Orbot status, debug log
│   └── _layout.tsx                   # Root layout — redirects to onboarding if identity not set
│
├── components/
│   ├── onboarding/
│   │   ├── StepIndicator.tsx         # 3-dot progress indicator for onboarding steps
│   │   ├── IdentityCreator.tsx       # Handle input → keypair generation → display result
│   │   ├── FeedCategoryPicker.tsx    # Grid of feed categories with toggle chips
│   │   └── ConnectionTutorial.tsx    # Animated walkthrough: open QR scanner → scan peer QR
│   ├── feed/
│   │   ├── FeedCard.tsx              # Single feed item card (swipeable)
│   │   ├── SourceList.tsx            # Feed source management UI
│   │   └── ArticleReader.tsx         # In-app reader (Readability.js extraction)
│   ├── social/
│   │   ├── PostCard.tsx              # Mesh post card (signed, gossip badge)
│   │   ├── ComposeModal.tsx          # Compose + sign + broadcast post
│   │   └── DMThread.tsx              # E2EE direct message thread
│   ├── identity/
│   │   ├── HandleDisplay.tsx         # "Handle.tripcode" with bold/dim split
│   │   ├── KeyDisplay.tsx            # Public key in JetBrains Mono, copyable
│   │   └── QRShare.tsx               # QR code for identity sharing + scanner
│   └── ui/
│       ├── Button.tsx
│       ├── Card.tsx
│       ├── Input.tsx
│       ├── Badge.tsx
│       └── DebugLog.tsx              # Scrollable, filterable debug log viewer
│
├── lib/
│   ├── crypto/
│   │   ├── identity.ts               # Ed25519 keypair, tripcode, Handle.Tripcode derivation
│   │   ├── signing.ts                # Sign/verify post payloads
│   │   ├── encryption.ts             # X25519 key exchange + ChaCha20-Poly1305 DMs
│   │   └── backup.ts                 # AES-GCM encrypted identity export/import
│   ├── tor/
│   │   ├── orbot.ts                  # Orbot detection, Intent launch, proxy-ready polling
│   │   ├── proxyFetch.ts             # fetch() wrapper via socks5://127.0.0.1:9050
│   │   └── onion.ts                  # Onion address utilities
│   ├── feeds/
│   │   ├── parser.ts                 # RSS/Atom/JSON Feed parser
│   │   ├── autodiscover.ts           # Auto-detect feed URL from any website URL
│   │   ├── sourceLibrary.ts          # Built-in curated source library with categories
│   │   └── backgroundSync.ts         # Expo Background Fetch handler
│   ├── mesh/
│   │   ├── gossip.ts                 # Gossip: INVENTORY_ANNOUNCE, daisy-chain, TTL
│   │   ├── peers.ts                  # Peer management, connection state, trusted list
│   │   ├── packets.ts                # Packet types, serialization, firewall
│   │   └── sync.ts                   # GLOBAL_SYNC_REQUEST / SYNC_RESPONSE
│   ├── storage/
│   │   ├── schema.ts                 # SQLite table definitions + migrations
│   │   ├── feeds.ts                  # Feed source + item CRUD
│   │   ├── posts.ts                  # Mesh post CRUD
│   │   ├── messages.ts               # DM message CRUD
│   │   └── peers.ts                  # Known peer storage
│   └── debug/
│       └── logger.ts                 # Ring buffer + file writer + clipboard export
│
├── store/
│   ├── feedStore.ts                  # Zustand: feed sources, items, unread counts
│   ├── socialStore.ts                # Zustand: mesh posts, gossip queue
│   ├── identityStore.ts              # Zustand: keypair, handle, tripcode
│   ├── peerStore.ts                  # Zustand: known peers, connection state
│   ├── torStore.ts                   # Zustand: Orbot status, proxy ready flag
│   └── onboardingStore.ts            # Zustand: onboarding completion state (persisted)
│
├── constants/
│   ├── bootstrap.ts                  # HAI-Net bootstrap peer addresses
│   ├── design.ts                     # Design tokens (mirrors tailwind.config.js)
│   └── protocol.ts                   # Packet type constants, TTL values, chunk sizes
│
├── assets/
│   ├── fonts/                        # JetBrains Mono + Sora font files
│   ├── icon.png
│   └── splash.png
│
├── docs/
│   ├── BUILD.md                      # How to build and run — see §9.1
│   ├── DEBUG.md                      # How to read and use debug logs — see §9.2
│   └── PROJECT_STATUS.md             # Current build state — updated every session
│
├── .gitignore                        # Comprehensive — see §1.2
├── README.md                         # Always reflects actual current state
├── LICENSE.md                        # AGPL-3.0 full text
├── app.json
├── eas.json                          # EAS build profiles (preview APK + production AAB)
├── tailwind.config.js
├── tsconfig.json                     # strict: true
└── package.json
```

---

## 4. Phase 0 — Foundation (Build This First)

**Exit criteria:** App runs on a physical Android device or emulator. Onboarding flow navigates
correctly. Design system applied. Debug logger writes to file. All root GitHub files present.

### 4.1 Project Initialisation

```bash
npx create-expo-app noslop-app --template expo-template-blank-typescript
cd noslop-app

npx expo install \
  expo-router expo-sqlite expo-secure-store expo-file-system \
  expo-clipboard expo-camera expo-background-fetch expo-task-manager \
  expo-intent-launcher expo-sharing

npm install \
  nativewind zustand zustand/middleware \
  rss-parser \
  @noble/curves @noble/ciphers @noble/hashes @scure/base \
  @shopify/flash-list \
  react-native-svg react-native-qrcode-svg

npm install -D \
  tailwindcss \
  prettier eslint \
  @typescript-eslint/eslint-plugin @typescript-eslint/parser
```

**Required `package.json` scripts:**
```json
{
  "scripts": {
    "start": "expo start",
    "android": "expo start --android",
    "type-check": "tsc --noEmit",
    "lint": "eslint . --ext .ts,.tsx",
    "build:apk": "eas build -p android --profile preview",
    "build:aab": "eas build -p android --profile production"
  }
}
```

**`eas.json` build profiles:**
```json
{
  "build": {
    "preview": {
      "distribution": "internal",
      "android": { "buildType": "apk" }
    },
    "production": {
      "android": { "buildType": "app-bundle" }
    }
  }
}
```

### 4.2 Root GitHub Files (Produce These First, Before Any Code)

**`.gitignore`** — must include at minimum:
```
node_modules/
.expo/
dist/
build/
*.apk
*.aab
.env
.env.local
.env.*.local
eas-credentials/
android/
ios/
.DS_Store
Thumbs.db
*.log
!docs/**/*.md
```

**`LICENSE.md`** — Full AGPL-3.0 text. Obtain from https://www.gnu.org/licenses/agpl-3.0.txt
and include verbatim. Do not summarise or paraphrase.

**`README.md`** — Initial content at Phase 0:
```markdown
# NoSlop

> Your feed. Your identity. No algorithm.

NoSlop is an open source Android app that combines a personal RSS/feed aggregator
with a serverless, Tor-routed social network node on the HAI-Net mesh.

**Status: Active development — Phase 0 (Foundation)**
See [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) for current build state.

## What works right now
- [ ] Project skeleton and navigation
- [ ] Design system (NativeWind)
- [ ] Debug logger

*(Updated as phases complete)*

## Build
See [docs/BUILD.md](docs/BUILD.md) for full instructions.

## License
AGPL-3.0 — see [LICENSE.md](LICENSE.md)
```

**`docs/PROJECT_STATUS.md`** — Template (update every session):
```markdown
# NoSlop — Project Status

**Last updated:** [DATE]
**Current phase:** [PHASE]
**Version:** v0.0.x

## ✅ Complete and Functional
*(Only list things with zero stubs or placeholders)*

## 🔨 In Progress
*(Current session work)*

## 📋 Planned
*(Future phases)*

## ⚠️ Known Issues
*(Bugs, limitations, technical debt)*

## 📦 Dependencies
*(List all npm packages added so far with versions)*
```

### 4.3 Debug Logger (Build Before Any Other Module)

**File:** `lib/debug/logger.ts`

This is built first because every other module imports it.

```typescript
/**
 * @module logger
 * @description Structured debug logger for NoSlop.
 *
 * Writes to:
 *   1. An in-memory ring buffer (last 500 entries) for the in-app DebugLog viewer.
 *   2. A newline-delimited JSON file at FileSystem.documentDirectory + 'noslop-debug.log'
 *      for ADB extraction and LLM debugging sessions.
 *
 * Usage:
 *   import { createLogger } from '../debug/logger';
 *   const log = createLogger('MODULE_NAME');
 *   log.info('Something happened', { detail: 'value' });
 *
 * Log levels: DEBUG < INFO < WARN < ERROR
 * Set minimum level via setMinLevel() — defaults to DEBUG in dev, INFO in production.
 */

export type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

export interface LogEntry {
  timestamp: string;   // ISO 8601
  level: LogLevel;
  module: string;      // e.g. 'TOR', 'GOSSIP', 'FEED', 'CRYPTO', 'FIREWALL', 'ONBOARDING'
  message: string;
  data?: string;       // JSON.stringify of any extra context — never log raw private keys
}

// Required exports:
export function createLogger(module: string): {
  debug: (message: string, data?: unknown) => void;
  info:  (message: string, data?: unknown) => void;
  warn:  (message: string, data?: unknown) => void;
  error: (message: string, data?: unknown) => void;
};

export function getLog(): LogEntry[];
export function copyToClipboard(): Promise<void>;  // Formats log as readable text then copies
export function clearLog(): void;                  // Clears ring buffer AND truncates log file
export function setMinLevel(level: LogLevel): void;
export const LOG_FILE_PATH: string;               // Exported for display in Settings UI
```

Implementation requirements:
- Ring buffer write is synchronous (just push to array, splice when > 500)
- File write is fire-and-forget async — never `await` it in the caller
- In dev mode: also write to `console.log` with colour coding
- In production: no console output — file and ring buffer only
- NEVER log raw private keys or seed phrases — if a crypto function passes key material,
  log only the public key or a truncated hash of it, with a comment explaining why

### 4.4 Onboarding Flow

The onboarding flow is the first thing a new user sees. It is NOT optional and NOT skippable.
The main tab navigator must redirect to onboarding if identity has not been created yet.

**`store/onboardingStore.ts`**
```typescript
// Persisted to SQLite (not just memory) so it survives app restart
interface OnboardingState {
  isComplete: boolean;           // true = skip onboarding on future launches
  identityCreated: boolean;      // Step 1 gate
  feedsConfigured: boolean;      // Step 2 gate
  connectionTutorialSeen: boolean; // Step 3 gate (can be skipped but must be shown)
}
```

**`app/onboarding/step1-identity.tsx` — Create Identity**

This screen must:
1. Show a brief one-sentence welcome: *"NoSlop gives you a cryptographic identity that belongs only to you."*
2. Show a text input for the user's chosen handle (alphanumeric, 1–20 chars, live validation)
3. Show a "Create Identity" button (disabled until handle is valid)
4. On button press:
   - Call `generateIdentity(handle)` from `lib/crypto/identity.ts`
   - Show a loading state ("Generating your keypair...")
   - On success: reveal the user's `Handle.Tripcode` display name using `HandleDisplay` component,
     their onion address (truncated, monospace, copyable), and a tick/green confirmation
5. Show a "Continue →" button that only appears after identity is successfully generated
6. Log the entire flow via `createLogger('ONBOARDING')`

**`app/onboarding/step2-feeds.tsx` — Set Up Feed Sources**

This screen must:
1. Show a headline: *"What do you want to read?"*
2. Display a scrollable grid of **ready-made category chips** from `lib/feeds/sourceLibrary.ts`.
   Categories must include real, working feed sources (not placeholder URLs):
   - **Technology** — Hacker News, Ars Technica, The Verge
   - **Privacy & Security** — Krebs on Security, Schneier on Security, EFF Deeplinks
   - **Self-Hosting** — r/selfhosted, r/homelab, Awesome-Selfhosted releases
   - **Science** — NASA, Phys.org, Science Daily
   - **World News** — Reuters, Associated Press, Al Jazeera
   - **Open Source** — Linux Foundation, GitHub trending (via RSS bridge), FOSS Post
   - **Podcasts** — User can add by RSS URL (show the input after selecting this category)
   - **YouTube** — Input a channel URL → auto-extract RSS feed
   - **Mastodon** — Input an account URL → auto-detect public RSS feed
   - **Reddit** — Input a subreddit → construct `old.reddit.com/r/{sub}.rss`
3. Tapping a category chip toggles it. Selected chips show in accent green.
4. "Custom URL" chip opens a URL input that calls `autodiscover.ts`
5. Minimum 1 source must be selected to proceed
6. "Start Reading →" button saves selected sources to SQLite and marks step 2 complete

**`app/onboarding/step3-connection.tsx` — Connection Tutorial**

This screen teaches the user how to add their first peer. It must:
1. Show a headline: *"Connect with someone on the mesh"*
2. Show a 3-step animated walkthrough (each step appears after a brief delay or tap):
   - **Step A:** *"Ask your friend to open their Profile tab and show their QR code"*
     — show a mock QR illustration (SVG, not an image file)
   - **Step B:** *"Tap the scan button below to open your camera"*
     — show the scan button (same button that appears on the real Contacts/Profile screen)
   - **Step C:** *"Once scanned, your devices will perform a secure handshake over Tor"*
     — show a simple animated diagram of two phones with a Tor onion icon between them (SVG)
3. A real "Scan a QR Code" button that launches the camera scanner (functional, not a demo)
   — if the user actually scans a valid peer QR during onboarding, initiate the handshake
4. A "Skip for now" text link at the bottom — this is the only skippable step
5. A "Done →" button that completes onboarding and navigates to the main feed tab

**`components/onboarding/StepIndicator.tsx`**
Three dots at the top of every onboarding screen. Current step is accent green, others are muted.

### 4.5 Root Layout Gate

**`app/_layout.tsx`**

On app launch, check `onboardingStore.isComplete`. If false, redirect to `/onboarding/step1-identity`.
If true, render the tab navigator normally. This check must happen before any other rendering.

### 4.6 Tor/Orbot Proof-of-Concept (Required Gate Before Phase 2)

**File:** `lib/tor/orbot.ts`

```typescript
/**
 * @module orbot
 * @description Orbot (Android Tor client) integration via Android Intent.
 *
 * Orbot exposes a SOCKS5 proxy on 127.0.0.1:9050 once running.
 * We detect it, launch it if needed, and poll until the proxy accepts connections.
 *
 * Why Intent-based rather than bundled Tor binary:
 *   Bundling a Tor binary requires native modules and significantly increases APK size.
 *   Orbot is maintained by the Tor Project and handles Tor process lifecycle correctly.
 *   The trade-off: user must install Orbot separately. This is surfaced clearly in the UI.
 */

export interface OrbotStatus {
  installed: boolean;
  proxyReady: boolean;
  lastChecked: Date;
}

// Query Android package manager for Orbot (package: org.torproject.android)
export async function isOrbotInstalled(): Promise<boolean>

// Launch Orbot via explicit Intent, request VPN/proxy mode
export async function launchOrbot(): Promise<void>

// Poll 127.0.0.1:9050 until a TCP connection succeeds (proxy accepting)
// or until timeoutMs elapses. Default timeout: 30s. Poll interval: 2s.
// Logs each attempt via createLogger('TOR').
export async function waitForProxy(timeoutMs?: number): Promise<boolean>

export async function getOrbotStatus(): Promise<OrbotStatus>
```

**Test card on Settings screen:**
Show a "Tor Status" card that displays Orbot installed (✓/✗), proxy ready (✓/✗), and a
"[Test Tor Connection]" button that calls `torFetch('https://check.torproject.org/')` and
displays "Tor is on" or the error message. This card must always be visible on the Settings screen.

---

## 5. Phase 1 — Feed Layer

**Exit criteria:** Can add feed sources, unified timeline shows real items, swipe gestures work,
background sync runs and logs.

### 5.1 SQLite Schema

**File:** `lib/storage/schema.ts`

```sql
-- All tables created with IF NOT EXISTS. Add a migrations table for future schema changes.
CREATE TABLE IF NOT EXISTS schema_version (
  version INTEGER PRIMARY KEY,
  applied_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS feed_sources (
  id TEXT PRIMARY KEY,
  url TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  icon_url TEXT,
  feed_type TEXT NOT NULL CHECK(feed_type IN ('rss','atom','json','youtube','reddit','mastodon')),
  category TEXT,
  last_fetched_at INTEGER,
  unread_count INTEGER DEFAULT 0,
  is_active INTEGER DEFAULT 1,
  added_during_onboarding INTEGER DEFAULT 0,  -- 1 if user selected during step 2
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS feed_items (
  id TEXT PRIMARY KEY,
  source_id TEXT NOT NULL REFERENCES feed_sources(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  url TEXT,
  author TEXT,
  excerpt TEXT,
  thumbnail_url TEXT,
  published_at INTEGER NOT NULL,
  is_read INTEGER DEFAULT 0,
  is_saved INTEGER DEFAULT 0,
  full_content TEXT,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS feed_filters (
  id TEXT PRIMARY KEY,
  pattern TEXT NOT NULL,
  action TEXT NOT NULL CHECK(action IN ('hide','boost')),
  source_id TEXT,
  created_at INTEGER NOT NULL
);
```

### 5.2 Source Library (Built-In Ready Choices)

**File:** `lib/feeds/sourceLibrary.ts`

```typescript
/**
 * @module sourceLibrary
 * @description Curated library of ready-to-use feed sources, grouped by category.
 * These are real, working feed URLs — not placeholders.
 * Used in onboarding step 2 and the Settings → Add Source screen.
 */

export interface BuiltInSource {
  id: string;           // Stable identifier (slug)
  title: string;
  url: string;          // Actual working RSS/Atom/JSON feed URL
  feedType: FeedType;
  category: string;
  iconUrl?: string;     // Direct URL to favicon or logo
}

export const SOURCE_LIBRARY: Record<string, BuiltInSource[]> = {
  'Technology': [
    { id: 'hn-rss',     title: 'Hacker News',       url: 'https://news.ycombinator.com/rss', feedType: 'rss', category: 'Technology' },
    { id: 'ars-rss',    title: 'Ars Technica',       url: 'https://feeds.arstechnica.com/arstechnica/index', feedType: 'rss', category: 'Technology' },
    { id: 'verge-rss',  title: 'The Verge',          url: 'https://www.theverge.com/rss/index.xml', feedType: 'atom', category: 'Technology' },
  ],
  'Privacy & Security': [
    { id: 'krebs-rss',  title: 'Krebs on Security',  url: 'https://krebsonsecurity.com/feed/', feedType: 'rss', category: 'Privacy & Security' },
    { id: 'schneier',   title: 'Schneier on Security', url: 'https://www.schneier.com/feed/atom/', feedType: 'atom', category: 'Privacy & Security' },
    { id: 'eff-dl',     title: 'EFF Deeplinks',      url: 'https://www.eff.org/rss/updates.xml', feedType: 'rss', category: 'Privacy & Security' },
  ],
  'Self-Hosting': [
    { id: 'r-selfhosted', title: 'r/selfhosted',     url: 'https://old.reddit.com/r/selfhosted.rss', feedType: 'rss', category: 'Self-Hosting' },
    { id: 'r-homelab',    title: 'r/homelab',         url: 'https://old.reddit.com/r/homelab.rss', feedType: 'rss', category: 'Self-Hosting' },
    { id: 'fosspost',     title: 'FOSS Post',         url: 'https://fosspost.org/feed', feedType: 'rss', category: 'Self-Hosting' },
  ],
  'Science': [
    { id: 'nasa-news',  title: 'NASA News',           url: 'https://www.nasa.gov/news-release/feed/', feedType: 'rss', category: 'Science' },
    { id: 'physorg',    title: 'Phys.org',            url: 'https://phys.org/rss-feed/', feedType: 'rss', category: 'Science' },
  ],
  'World News': [
    { id: 'reuters',    title: 'Reuters World',       url: 'https://feeds.reuters.com/reuters/worldNews', feedType: 'rss', category: 'World News' },
    { id: 'ap-top',     title: 'Associated Press',    url: 'https://feeds.apnews.com/rss/TopNews', feedType: 'rss', category: 'World News' },
    { id: 'aljazeera',  title: 'Al Jazeera',          url: 'https://www.aljazeera.com/xml/rss/all.xml', feedType: 'rss', category: 'World News' },
  ],
  // Add Open Source, Podcasts categories similarly with real URLs
};

// Returns all categories as display names
export function getCategories(): string[]

// Returns sources for a given category
export function getSourcesForCategory(category: string): BuiltInSource[]
```

### 5.3 Feed Parser

**File:** `lib/feeds/parser.ts`

Use `rss-parser`. Handle without throwing: RSS 2.0, Atom 1.0, JSON Feed 1.1, YouTube channel
RSS (extract `yt:videoId` for thumbnail), Reddit RSS (strip HTML from `content:encoded`),
Mastodon public account RSS. Malformed feeds: log the error, return partial results.

### 5.4 Feed Card

**File:** `components/feed/FeedCard.tsx`

Using `@shopify/flash-list` for the feed list (not FlatList — performance requirement).
FeedCard implements swipe-left (dismiss/mark read) and swipe-right (save) gestures.

---

## 6. Phase 2 — Social Layer (gChat Mesh Port)

**Exit criteria:** Identity generated, Orbot connected, can post a signed message visible on a second
device, can send and receive an E2EE DM.

> **Before writing any code in this phase:** Read gChat's `cryptoService.ts`, `useNetworkLayer.ts`,
> `useGossipProtocol.ts`, and `useDiscovery.ts` in full. The logic ports directly — adapt syntax only.

### 6.1 Identity System

**File:** `lib/crypto/identity.ts`

Port from gChat's `cryptoService.ts`. Replace `tweetnacl` with `@noble/curves`.

```typescript
import { ed25519 } from '@noble/curves/ed25519';
import { sha3_256 } from '@noble/hashes/sha3';
import { base32 } from '@scure/base';

/**
 * @module identity
 * @description Cryptographic identity for NoSlop / HAI-Net mesh nodes.
 *
 * Each identity consists of:
 *   - An Ed25519 signing keypair (for signing posts and verifying authorship)
 *   - An X25519 encryption keypair (for E2EE DMs, derived from signing keypair)
 *   - A Handle.Tripcode display name (human-readable + cryptographically unique)
 *   - A Tor v3 .onion address derived from the signing public key
 *
 * Private keys are stored ONLY in expo-secure-store.
 * The public profile (public keys, handle, tripcode, onion address) is stored in SQLite.
 *
 * Tripcode derivation (compatible with gChat mesh):
 *   SHA3-256(signingPublicKey) → Base32 encode → take first 6 chars → lowercase
 *
 * Onion address derivation (Tor v3, compatible with gChat mesh):
 *   Port of gChat's deriveOnionAddress() — do not deviate or mesh compatibility breaks.
 */

export interface NoSlopIdentity {
  handle: string;
  tripcode: string;
  displayName: string;              // "handle.tripcode"
  signingPublicKey: Uint8Array;
  signingPrivateKey: Uint8Array;    // NEVER log, NEVER store outside SecureStore
  encryptionPublicKey: Uint8Array;
  encryptionPrivateKey: Uint8Array; // NEVER log, NEVER store outside SecureStore
  onionAddress: string;
}

export async function generateIdentity(handle: string): Promise<NoSlopIdentity>
export function deriveTripcode(signingPublicKey: Uint8Array): string
export function deriveOnionAddress(signingPublicKey: Uint8Array): string
export async function loadIdentity(): Promise<NoSlopIdentity | null>
export async function saveIdentity(identity: NoSlopIdentity): Promise<void>
```

### 6.2 Packet Types and Firewall

**File:** `lib/mesh/packets.ts`

Port from gChat's `useNetworkLayer.ts`. Packet types must match exactly for mesh compatibility.
The firewall rule is absolute: drop ALL packets from non-trusted senders except
`CONNECTION_REQUEST` and `USER_HANDSHAKE`. Log every drop with `createLogger('FIREWALL')`.

### 6.3 Gossip Protocol

**File:** `lib/mesh/gossip.ts`

Port from gChat's `useGossipProtocol.ts`. Improvements over gChat:
- Probabilistic TTL decay: halve broadcast probability per hop beyond 3 (reduces flooding)
- Rate limit: 20 packets per sender per 10-second window (port from gChat, already battle-tested)
- Dedup Set: cap at 1000 entries, evict oldest 100 when full

### 6.4 E2EE Direct Messages

**File:** `lib/crypto/encryption.ts`

```typescript
import { x25519 } from '@noble/curves/ed25519';
import { chacha20poly1305 } from '@noble/ciphers/chacha';

/**
 * X25519 Diffie-Hellman key exchange + ChaCha20-Poly1305 AEAD encryption.
 *
 * Why ChaCha20-Poly1305 over AES-GCM:
 *   ChaCha20 has no timing side-channels without hardware acceleration.
 *   @noble/ciphers implements it in pure JS, audited, no native deps required.
 *   Compatible with gChat's encryption scheme — mesh DMs remain interoperable.
 */

export function encryptMessage(
  plaintext: string,
  theirEncryptionPublicKey: Uint8Array,
  myEncryptionPrivateKey: Uint8Array
): { nonce: string; ciphertext: string }   // both base64

export function decryptMessage(
  ciphertext: string,
  nonce: string,
  theirEncryptionPublicKey: Uint8Array,
  myEncryptionPrivateKey: Uint8Array
): string | null   // null on failure — never throw
```

### 6.5 Orbot Full Integration

The `social/index.tsx` screen must gate all social features behind Orbot readiness:
- Orbot not installed → install prompt with F-Droid and Play Store links
- Installed but not running → "Start Orbot" button → `launchOrbot()` → poll `waitForProxy()`
- Proxy ready → full compose + peer UI visible
- Persistent status pill in tab bar: green "Tor" / amber "Connecting…" / red "Tor Offline"

---

## 7. Phase 3 — Polish and Completions

**Exit criteria:** Debug log is copyable and useful, all Settings sections complete, README
reflects v0.01 status, PROJECT_STATUS.md is accurate.

### 7.1 Debug Log Viewer

**File:** `components/ui/DebugLog.tsx`

Full-screen scrollable log with:
- Filter chips by level (DEBUG/INFO/WARN/ERROR) and by module (TOR/GOSSIP/FEED/CRYPTO/FIREWALL/ONBOARDING)
- Each entry: timestamp (monospace), coloured level badge, module badge, message
- "Copy All" → `Clipboard.setStringAsync(formattedLog)`
- "Share File" → `Sharing.shareAsync(LOG_FILE_PATH)`
- "Clear" → `logger.clearLog()`
- Auto-scroll to bottom (toggle-able)
- Log file path displayed at top, copyable

### 7.2 Settings Screen

All sections must be complete — no "coming soon" stubs:

**Feed** — manage sources, background sync interval, reader preference (in-app / external browser)

**Social / Tor** — Orbot status card, your onion address (copyable), bootstrap node list (editable),
peer management (list trusted peers, remove peer)

**Identity** — display name, public key (copyable, JetBrains Mono), export/import encrypted backup

**Debug** — debug log viewer, log file path (copyable), log level selector, Share Log File button

---

## 8. Required Documentation (Non-Negotiable Deliverables)

### 8.1 `docs/BUILD.md`

Must contain, in this exact order:

1. **Prerequisites** — exact tool versions: Node.js, npm, Expo CLI, EAS CLI, Android Studio
   version (or minimum Android SDK API level for physical device sideloading), Java version,
   whether a physical device or emulator is recommended and why
2. **Clone and install** — exact commands from `git clone` through `npm install`
3. **First run on emulator** — exact command sequence including how to create an AVD if needed,
   expected output of a successful launch
4. **First run on physical device** — how to enable USB debugging (Settings → About → tap
   Build Number 7 times → Developer Options → USB Debugging), `adb devices` to verify,
   then the exact expo run command
5. **First launch checklist** — what the user should see, screen by screen, on a successful
   first run: onboarding step 1 → step 2 feed selection → step 3 connection tutorial → main feed
6. **Build a debug APK** — exact EAS CLI command, how to download from EAS dashboard,
   how to install via `adb install noslop.apk`
7. **Common errors and fixes** — minimum 5 entries:
   - Metro bundler port conflict (kill process, restart)
   - NativeWind styles not applying (tailwind.config.js misconfiguration, fix steps)
   - SQLite migration failure (how to identify in debug log, how to reset app data)
   - Orbot Intent permission denied (AndroidManifest.xml query element missing, fix)
   - EAS build fails on missing credentials (eas credentials setup steps)

### 8.2 `docs/DEBUG.md`

Must contain, in this exact order:

1. **Where logs are written** — exact path on device: `[FileSystem.documentDirectory]noslop-debug.log`
   and what that resolves to for the `com.noslop.app` package
2. **Log format** — show an example log line, explain every field
3. **Copying logs from the app** — Settings → Debug → Copy All → paste into message
4. **Pulling logs via ADB** — exact commands:
   ```bash
   # Stream live to terminal
   adb shell run-as com.noslop.app cat /data/data/com.noslop.app/files/noslop-debug.log

   # Pull to local machine
   adb pull /data/data/com.noslop.app/files/noslop-debug.log ./noslop-debug.log
   ```
5. **Key modules and what each reveals** —
   ONBOARDING (first-run flow), TOR (proxy status), GOSSIP (packet propagation),
   FEED (parse errors, fetch failures), CRYPTO (key ops — never logs private material),
   FIREWALL (dropped packets with sender ID and reason)
6. **Annotated example** — a realistic 15-line debug log excerpt from a successful first
   launch, with inline annotations explaining what each line means
7. **Providing logs for LLM debugging** — exact instructions:
   > Copy the full log from Settings → Debug → Copy All and paste it at the top of your
   > next message to the LLM, followed by a description of what you saw happen on screen
   > and what you expected to happen. The LLM can then identify the exact failure point
   > from the log and provide corrected file(s).

---

## 9. Phase Execution Order and Gates

Execute in exact order. Do not start a phase until the gate condition is verified.

```
Phase 0: Foundation
  ├── 0.1  .gitignore + README.md + LICENSE.md + docs/PROJECT_STATUS.md created
           ← GATE: All 4 root files present and contain real content (not placeholders)
  ├── 0.2  Dependencies installed, TypeScript compiles with tsc --noEmit
           ← GATE: Zero type errors on a clean build
  ├── 0.3  Debug logger: createLogger works, log file created on device
           ← GATE: Settings screen shows log file path; tapping "Copy All" puts text in clipboard
  ├── 0.4  Expo Router + all tabs + onboarding flow navigates correctly on device
           ← GATE: Completing onboarding steps 1-3 lands on feed tab; subsequent launch skips onboarding
  └── 0.5  Orbot PoC: installed check + launch + proxy poll + test fetch
           ← GATE: Settings Tor card shows "Tor is on" from a real check.torproject.org fetch

Phase 1: Feed Layer
  ├── 1.1  SQLite schema initialised
           ← GATE: debug log shows all tables created on app start
  ├── 1.2  sourceLibrary.ts: all built-in sources have real, working URLs
           ← GATE: manually fetch 3 sources from the library, all parse without error
  ├── 1.3  parseFeed() handles RSS, YouTube, Reddit
           ← GATE: 3 different source types parsed, items appear in unified feed on device
  ├── 1.4  FeedCard swipe gestures (dismiss + save) work on device
           ← GATE: swipe left removes item from feed, swipe right shows saved badge
  └── 1.5  Background sync registered
           ← GATE: debug log shows FEED backgroundSync ran after app is backgrounded 15 min

Phase 2: Social Layer
  ├── 2.1  Identity generation: keypair, tripcode, onion address
           ← GATE: onion address derivation matches gChat's expected output for same input key
  ├── 2.2  Orbot full integration in social tab
           ← GATE: Tor status pill updates correctly through installed → connecting → ready states
  ├── 2.3  Sign + verify round-trip
           ← GATE: sign a test payload, verify returns true; mutate payload, verify returns false
  ├── 2.4  Packet firewall
           ← GATE: debug log shows FIREWALL DROP for a packet with unknown senderId
  ├── 2.5  Gossip post broadcast between two devices
           ← GATE: post composed on device A appears on device B's social feed
  └── 2.6  E2EE DM between two devices
           ← GATE: DM sent from device A decrypted correctly on device B; wrong key returns null

Phase 3: Polish
  ├── 3.1  Debug log viewer complete and useful
           ← GATE: Copy All produces a valid, filterable log including TOR and GOSSIP entries
  ├── 3.2  Settings screen: all sections functional, no stubs
           ← GATE: every Settings action produces a real result or a real error (nothing is fake)
  └── 3.3  Docs complete: BUILD.md, DEBUG.md, PROJECT_STATUS.md accurate
           ← GATE: a person following BUILD.md from scratch can run the app on a device
```

---

## 10. Context Loading Template for Each Session

Use this template verbatim at the start of every build session:

```
# NoSlop Android Build Session

## Project
Building the NoSlop Android app for the HAI-Net decentralised mesh.
Stack: Expo + React Native + TypeScript strict + NativeWind + expo-sqlite + @noble/curves + Expo Router
License: AGPL-3.0
Repo: github.com/[your-username]/noslop-app

## Permanent Rules (apply every session, no exceptions)
- NO mocks, NO placeholders, NO simulated data. Fully functional code only.
  The project is not done until ALL code is functional.
- NO Google products, NO Gemini API, NO Firebase, NO Google Play Services APIs.
  Do not include these unless explicitly asked.
- TypeScript strict: no any without a comment, no @ts-ignore
- Comments on every non-obvious decision
- Social layer: Tor SOCKS5 only — never clearnet, never as fallback
- No analytics, no telemetry, no third-party crash reporting
- Crypto: @noble/curves + @noble/ciphers only
- Storage: expo-sqlite only
- Log everything via createLogger('[MODULE]')
- Every session must update: README.md, docs/PROJECT_STATUS.md
- .gitignore, LICENSE.md (AGPL-3.0), README.md must always be present and accurate

## Current Phase
[e.g. "Phase 2.3: Implement sign + verify in lib/crypto/signing.ts"]

## Completed Files (do not rewrite unless fixing a bug)
[List completed files from previous sessions]

## Files Needed for This Session
[Paste full content of files this session will read or extend]

## gChat Reference (if porting mesh code)
[Paste relevant gChat source sections]

## Task
Build: [SPECIFIC DELIVERABLE from the phase plan]

## Output Format
- Complete file(s), TypeScript strict, ready to commit
- Path comment at top of each file: // FILE: lib/crypto/signing.ts
- If you deviate from the plan, explain why in a code comment AND in a
  brief "Deviations" note after the code block
- Update docs/PROJECT_STATUS.md to reflect what this session completed
```

---

## 11. Debugging Workflow

When something breaks:

1. Reproduce the failure on device
2. Settings → Debug → Copy All
3. Open a new LLM session with:
   ```
   # NoSlop Debug Session

   ## Failure
   [What happened on screen vs what was expected]

   ## Full Debug Log
   [PASTE FULL LOG HERE]

   ## Relevant Files
   [Paste the files most likely involved — check FIREWALL/TOR/GOSSIP modules first]

   Please identify the root cause from the log and provide corrected file(s).
   If the log doesn't contain enough information to diagnose the issue,
   tell me which createLogger() calls to add first, then I'll reproduce and send the log again.
   ```
4. Apply fix, rebuild, test, update PROJECT_STATUS.md

---

## 12. gChat Porting Reference

| gChat Source | NoSlop Target | Migration Note |
|---|---|---|
| `cryptoService.generateKeys()` | `lib/crypto/identity.ts` | tweetnacl → @noble/curves |
| `cryptoService.generateTripcode()` | `lib/crypto/identity.ts` | SHA3-256 → Base32 → 6 chars. Must match exactly. |
| `cryptoService.deriveOnionAddress()` | `lib/crypto/identity.ts` | Copy Tor v3 derivation verbatim — mesh compatibility |
| `cryptoService.signData()` | `lib/crypto/signing.ts` | deterministic JSON stringify before signing |
| `cryptoService.encryptMessage()` | `lib/crypto/encryption.ts` | nacl.box → @noble X25519 + ChaCha20-Poly1305 |
| `useGossipProtocol.broadcastPostState()` | `lib/mesh/gossip.ts` | INVENTORY_ANNOUNCE + daisy-chain |
| `useNetworkLayer` firewall | `lib/mesh/packets.ts` | Drop untrusted — do NOT soften this rule |
| Dual-agent transport | `lib/tor/proxyFetch.ts` | Control: 8s timeout; Data: keep-alive, 120s timeout |
| `packetRateLimit` Map | `lib/mesh/gossip.ts` | 20 packets / 10s per sender |
| `processedPacketIds` Set | `lib/mesh/gossip.ts` | Cap at 1000, evict oldest 100 |
| `secureLog` redaction | `lib/debug/logger.ts` | Never log raw private keys or seed phrases |

**gChat bugs explicitly NOT ported:**

| gChat Issue | NoSlop Fix |
|---|---|
| localStorage for all storage (5MB limit crash) | expo-sqlite only |
| tweetnacl dependency | @noble/curves + @noble/ciphers |
| Simple gossip flooding (high idle bandwidth) | Probabilistic TTL decay after hop 3 |
| FlatList for feed rendering (sluggish at 100+ items) | @shopify/flash-list |
| Ephemeral messages not actually deleted | Implement a SQLite garbage collector (scheduled via expo-task-manager) |

---

*NoSlop app is the wedge. HAI-Net is what it opens the door to. Build it real.*
