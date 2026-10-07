# NoSlop: creator discovery, community mirroring, and Home HUB enhancement plan

Status: development handoff / proposed design, not implemented
Revision: 2 — grounded in both the Android and HAI-Net source archives
Date: 7 October 2026 (Australia/Perth)
Owner: Gabor Kukucska (Mr Megadream), creator of NoSlop and filmmaker behind INSPIREFLIX
Target: legacy Android application and its Home HUB integration. Ignore MVP/iOS/migration redesigns.
Source baseline: user-supplied `NoSlop-main(10).zip`; archive header e73dcd558fea6d1a05924f89c07b0d6d9c8418f7. Revalidate against the actual development branch before editing.
Home HUB baseline: user-supplied `hai-master.zip`; archive header db6ec6c01d5cc01a63e7b972e33527820f6c3882. Its contents predate the Android snapshot. Neither archive was built, deployed, or runtime-tested for this review.

## 1. Instructions to the implementing LLM

Implement this plan incrementally when instructed to implement it. This document itself does not claim implementation, validation, or deployment has occurred. Inspect repository instructions and current code first. Preserve existing working feed controls, identity separation, Tor routing, DMs, group security, and backup/recovery behaviour. Do not weaken the nonce-bound handshake to make offline following work.

Distinguish three evidence levels throughout your work:

- **Agreed direction:** explicit product choices recorded below.
- **Recommended design:** a concrete implementation approach proposed here; adapt to repository evidence without changing the agreed product intent.
- **Unresolved decision:** make the trade-off visible before implementing an irreversible protocol or identity change.

Do not interpret identifiers or field names in this document as existing API names unless listed in the code-evidence section. Do not invent Home HUB capabilities absent from its actual implementation. HUB sources have now been supplied and inspected; use the findings in section 14. Read HAI-Net's `helperfiles/0_DEVELOPMENT_RULES.md` when changing that repository, and resolve its status-document paths against files actually present. Treat completion labels in old integration documents as claims to verify against running code.

## 2. Objective and agreed direction

Make public creators discoverable during temporary outages, allow opt-in community devices to serve their already-published public content, and reduce dependence on the default NoSlop node. Preserve creator attribution, signed authorship, and creator-controlled donation links. Allow a user-owned Home HUB to take over their node's work and free the phone from continuous service duties.

Agreed product rules:

1. Creator/discoverable nodes exchange creator discovery metadata. A creator-to-creator discovery connection must not automatically subscribe, download, store, or relay the other creator's content.
2. Only non-creator nodes may act as community content proxies/mirrors for other creators in the initial design. Creator nodes still publish and serve their own content. Ordinary viewing is not prohibited by this role rule.
3. Following a creator is a local action that can succeed without a live creator handshake. Reading available mirrored public content must also work while the creator is offline.
4. Mirroring requires explicit opt-in, ideally alongside Follow/Subscribe, with per-creator controls and an overall resource budget. Following alone is not consent to upload or host content.
5. Creator identity, authorship information, and payment links must remain verifiable and unmodified when content comes from a mirror.
6. Community mirrors use their own identities/endpoints. They do not receive the creator's private signing or encryption keys.
7. The official NoSlop node remains an optional bootstrap/discovery entry point, initially on the user's Galaxy Tab. It must obey the same bounded discovery rules as other creator nodes.
8. A successful bootstrap sync should persist alternative reachable endpoints for future use. A fresh installation still needs some reachable entry point, supplied by the default node, an invitation, or a previously imported bootstrap record.
9. Home HUB is an execution location for the user's node role, not a loophole that lets a creator node mirror other creators contrary to rule 2.

Terminology: use **mirror** or **content proxy** for community hosting. Reserve **owner-controlled node handover** for moving service duties to the user's own HUB. Neither means that a follower becomes the creator.

## 3. Evidence from the supplied Android snapshot

These are source observations, not device-test results. Recheck call sites and packet forwarding before assuming end-to-end behaviour.

| Area | Observed code | Consequence |
| --- | --- | --- |
| Discovery exchange | `data/MeshSocialRepository.kt`, `shareDiscoverableNodesWith()` forwards cached `disc_packet_<publicKey>` packets for creator records | There is an existing foundation for metadata relay |
| Discovery selection | `data/Daos.kt`, `getDiscoverablePeersList()` selects discoverable, untrusted peers | Already-followed/trusted creators may be excluded; audit rather than assuming all creators propagate |
| Discovery reception | `mesh/HandshakePacketHandler.kt`, `handleAnnounceDiscoverable()` verifies signed announcements and ignores timestamps more than 30 minutes from local time | Simply extending database retention will not make old announcements usable |
| Presence coupling | The same handler sets `isOnline=true` and `lastSeenAt=now` on reception of relayed discovery | Relayed metadata currently implies reachability without a direct connection |
| Cleanup | `MeshSocialRepository.kt` marks offline after 3 minutes, removes untrusted discovery records after 15 minutes, clears discovery flags for trusted ones, and has a 30-day peer deletion path | Separate discovery-cache retention from relationship/content deletion; do not extend one timer blindly |
| Handshake | `HandshakePacketHandler.kt` auto-accepts eligible creator-addressed requests; trust promotion is nonce/state-bound | Offline public subscriptions must be a separate state from accepted contacts |
| HUB deployment | `net/SshDeployer.kt` can transfer owner identity material through SSH with host-key checks | Existing owner-HUB key transfer is distinct from prohibited community key sharing |
| HUB sync | `data/HubSyncWorker.kt` performs encrypted backup upload, including a same-identity onion fallback path | Backup support does not prove active service takeover, endpoint ownership, or split-brain safety |

Additional relevant files: `mesh/Packets.kt`, `mesh/MeshPacketVerifier.kt`, `mesh/GossipService.kt`, `mesh/MediaManager.kt`, `mesh/MediaProxyService.kt`, `data/Entities.kt`, `data/NoSlopRepository.kt`, `crypto/CryptoService.kt`, `tor/TorService.kt`, `docs/WIRE_PROTOCOL_REFERENCE.md`, `docs/TECHNICAL_REFERENCE.md`.

## 4. Architecture and trust boundaries

Separate **creator identity**, **public subscription**, **trusted personal contact**, **service endpoint**, and **content availability**. Do not overload one `Peer.isTrusted` or `isOnline` field to express all five.

| Role | Discovery exchange | Own creator publishing | Community mirroring |
| --- | --- | --- | --- |
| Creator node | Opt-in, bounded metadata exchange | Yes | No |
| Non-creator phone | May cache/share permitted public discovery | No creator role enabled | Explicit opt-in only |
| Official NoSlop creator | Same discovery rules as other creators | Yes | No |
| Owner's Home HUB | Inherits the hosted node's role and settings | If hosting a creator node | If hosting a non-creator mirror node |

Creator discovery metadata must remain isolated from personal contact identities and private relationship graphs. The creator's public/burnable identity is the relevant public identity. Do not advertise the user's personal onion endpoint or private contacts.

The creator-to-creator exchange may carry public creator cards and bounded mirror availability notices, but not post bodies, media, comments, or content inventories that grow with the full publication history. Content requests must use a separate path with appropriate policy checks.

Do not assume the wider mesh removes third-party feed/API proxy requirements. Cloudflare proxy replacement is outside this enhancement.

## 5. Recommended protocol records

Design versioned, canonically encoded records using the project's cryptographic conventions. Bind all security-relevant fields to the signature, with record-type/domain separation. Do not concatenate ambiguous strings for new protocol formats. Reuse verified existing cryptographic primitives; do not invent cryptography.

### 5.1 Creator card

Proposed fields: schema version, creator public key/ID, public profile, original creator endpoint, donation URL, mirror permission/policy version, monotonic record revision, issued time, expiry policy, signature.

The creator signs the card. Mirrors forward it unchanged. Authorship derives from the creator key, not the transport endpoint or display handle. Address rotation requires a creator-signed update; creator-key/burnable-identity rotation is a separate lifecycle decision, not silently treated as the same identity.

Keep last verified card revision persistently enough to prevent ordinary rollback. A brand-new/offline client cannot know about an update it has never seen. Same-revision conflicts must be detected and surfaced rather than arbitrarily merged. Clock skew and future-dated records need bounded handling.

### 5.2 Public post envelope

Bind creator identity, stable post ID/revision, content/media hashes, public visibility, attribution, and relevant creator-card/policy reference to the creator signature. Preserve existing valid posts through an explicit compatibility strategy; do not require a mirror to forge a new creator signature for legacy data.

The receiver verifies signatures, hashes, size limits, and publication policy before rendering/storing. A proxy must not rewrite creator names, payment links, post text, or authorship metadata. Rendering should distinguish **published by** from **served by**.

### 5.3 Mirror availability notice

Proposed fields: schema version, mirror public key, mirror onion endpoint, creator ID served, coarse capabilities, issued time, expiry, revision, mirror signature. Avoid full media inventories in global discovery.

This is a mirror's claim of service availability, not proof of creator endorsement, capacity, or live reachability. Verify actual service behaviour. If the creator requires named mirrors, attach a creator-signed grant scoped to mirroring public content. A public creator-signed permission can alternatively allow any consenting mirror; choose the policy explicitly.

An **authorised mirror** badge must be justified by the applicable creator grant/policy. Otherwise label transport provenance without implying endorsement. Signatures prove association with a key, not independent legal ownership of copyright. Preserve attribution and any supplied licence; do not present cryptographic verification as a copyright adjudication system.

### 5.4 Updates, withdrawals, and deletion

Specify signed profile updates, endpoint withdrawals, mirror permission changes, and public-post tombstones. Honest mirrors should honour them when received. Expiry bounds stale service claims; it cannot guarantee immediate revocation or deletion on offline/hostile devices. Never promise recall of already-distributed public content.

## 6. Availability, subscriptions, and content fetching

Track at least these independent concepts: latest signed creator revision, original issue/expiry information, last receipt via gossip, last successful direct contact, local subscription state, known mirror routes, local mirror consent/configuration.

Suggested user states: subscribed; original reachability unknown; original reachable; mirror available; connection pending; cached content only. Receiving an announcement must not by itself mark the original node online.

Fetch flow:

1. Discover and verify a creator card through a reachable peer.
2. Follow locally without establishing a trusted personal contact.
3. Try the original endpoint using bounded timeouts; fall back to a small set of known mirrors, respecting user policy.
4. Fetch a bounded inventory/page on the content path, then selected public content.
5. Verify signed envelopes and media hashes; reject corruption and misleading attribution.
6. Retry unavailable routes with jittered backoff and cancellation; avoid blocking the feed/UI.
7. Establish the existing authenticated direct handshake only when needed and possible. Do not upgrade local subscriptions to trusted contacts automatically.

No private DMs, private groups, encryption secrets, or restricted audience posts enter the public mirror cache. Review existing gossip paths so creator-to-creator transport does not accidentally flood content despite the new discovery policy.

Payment links are read from verified creator records, not live-only original-node requests or mirror-controlled metadata. Attempt a bounded update check when practical before opening a donation destination. Show when creator information is cached; preserve the signed destination and prevent proxy-added redirects/affiliate substitutions. Normal URL validation still applies. Resolve stale-link UX explicitly before shipping.

## 7. Discovery and bootstrap scaling

Exchange bounded peer samples and incremental changes. Do not repeatedly transmit the complete directory or re-broadcast unchanged records with fresh outer IDs to defeat deduplication. Deduplicate by signed record identity/revision or content digest as appropriate.

Select samples using locally observed reachability, available capacity hints, rotation, and randomness. Do not promote only the historically most reliable nodes forever. Distinct public keys alone do not establish distinct operators; avoid claiming Sybil resistance from diversity-by-key.

Enforce limits on inbound connections/handshakes, record size, records per batch, bytes per time window, queues, per-peer work, retained cards, retained mirror routes per creator, and retry concurrency. Provide overload rejection/backoff without unbounded pending state. Unsupported/stale announcements must not keep re-entering gossip indefinitely.

Keep creator-card retention longer than short presence timeouts, but bounded. Retain followed creators as subscriptions independently of discovery-cache eviction. Expiring a route must not delete DMs, subscriptions, or user-saved content. Audit the existing 30-day peer deletion logic for unintended interactions.

After bootstrap, persist several alternative endpoints with their capabilities and freshness. Try cached alternatives if the official node fails. Allow invitations/manual onboarding through another peer, including when the user declines the official node. Cached alternatives are not a guarantee of availability; all may go offline.

## 8. Mirror controls and UX

Add explicit **Help keep this creator available** opt-in beside Follow/Subscribe. Expose per-creator controls through the creator profile/DM entry where appropriate and a central settings page, provisionally **Community hosting**. Share reusable control components with Creator Studio, but never show a mirror as having publishing authority over someone else's channel.

Per-creator controls: enabled/paused state, storage quota, retained content age/count, metadata/text versus images/full media selection, upload allowance/rate, transfer concurrency, network conditions, charging/battery rules, and stop/delete-cache action.

Global controls override per-creator allocations: total disk, bandwidth/data allowance, concurrency, minimum free space, battery/thermal conditions, operating schedule, and emergency pause. Specify fair scheduling so one popular creator cannot consume all resources. Mirror availability notices should reflect pause/withdrawal/capability changes without disclosing unnecessary personal device details.

Switching a mirror node into creator mode must stop new community mirroring and withdraw advertisements according to a documented transition. Decide whether active transfers finish or cancel; preserve subscriptions and user-saved content. Disabling mirroring must not silently unfollow the creator.

## 9. Home HUB takeover

The HUB should be able to run discovery, the user's original creator publishing/serving OR non-creator mirroring, and other supported node services while the phone becomes primarily a controller/client. Retain per-creator and global controls, with a separate HUB resource profile where needed. A HUB hosting a creator role does not become a community mirror under this initial policy.

Trust distinction: a user-owned HUB may be explicitly authorised to hold the owner's identity material using the established secure deployment flow. A community mirror must never receive that material. Do not generalise the existing SSH identity-copy mechanism into follower mirroring.

Before implementation, decide how service identity and endpoint ownership transfer:

- Same public endpoint/identity: requires explicit single-active-service coordination and safe failure recovery. Do not simply launch both phone and HUB as competing owners of the same onion service.
- Distinct HUB endpoint with owner-signed binding: requires route updates and an authenticated control/signing model; it must not change public authorship or payment provenance.

Recommended handover state machine: phone active -> HUB preparing -> authenticated capability/readiness verification -> state synchronisation -> explicit service ownership transfer -> HUB active / phone controller. On failure, retain a recoverable prior state. Network partition alone must not trigger unsafe automatic dual ownership; document fencing/lease/recovery assumptions.

Verify role/configuration transfer, keys, subscriptions, publication revisions, mirror inventory, quotas, queued work, and signed endpoint announcements as applicable. Secure the control channel and preserve SSH host-key verification. Distinguish encrypted backup from live state synchronisation and service takeover.

Show which device is serving the node, whether the phone can sleep, last HUB contact, and how to recover or deliberately return service to the phone. Validate with the phone fully offline, not merely with its screen off. Do not claim complete takeover unless actual HUB capabilities are implemented and tested.

## 10. Implementation sequence and completion gates

### Phase 0: establish actual baseline
Map packet flow, security verification, discovery queries, role settings, UI entry points, cleanup, and HUB availability. Reproduce current four-device behaviour. Produce a short gap list against this plan. Identify compatibility requirements and missing HUB sources before writing migrations.

### Phase 0H: HUB integration prerequisites (required before HUB-dependent phases)
Apply section 14: secure per-client API authentication, bounded verified ingress, durable acknowledged packet delivery, explicit Android/Rust protocol compatibility, and tested identity import. Define the actual API contract instead of relying on documented routes absent from the supplied implementation. This prerequisite can proceed independently of Android-only discovery work, but HUB takeover and public HUB rollout must not bypass it.

### Phase 1: independent discovery and subscription state
Introduce schema/migrations separating signed creator records, liveness, routes, and subscriptions. Stop treating relayed announcements as direct presence. Preserve contact security. Add bounded retention and subscription UI states. Gate: cached discovery survives a short creator outage without falsely showing online or deleting user data.

### Phase 2: metadata-only creator discovery
Version discovery exchanges; distribute bounded cards and mirror route metadata. Enforce creator role restrictions across all packet paths, not only the UI. Persist alternative bootstrap routes. Gate: creator-to-creator discovery sends no other creator's post/media payloads and works through another reachable peer without the official node.

### Phase 3: opt-in public mirroring
Implement text/public-post verification and fetch before large media. Add per-creator/global budgets, controls, withdrawal, donation provenance, and explicit permission policy. Gate: newcomer reads verified public content while original creator is offline; invalid/tampered data is rejected and budgets hold.

### Phase 4: media and operational resilience
Integrate existing content hashes and transfer components; add bounded inventory, interrupted transfer recovery, fair scheduling, Android background lifecycle support, and overload handling. Gate: sleep/reconnect/churn tests pass without retry storms, excessive disk, or private-content leakage.

### Phase 5: owner Home HUB handover
Implement against the real HUB protocol and sources, with explicit compatibility negotiation and ownership control. Gate: intended services work with phone offline; failed handover does not lose identity/state or create competing active ownership.

### Phase 6: release readiness
Document capabilities, limitations, packet changes, controls, migration behaviour, and recovery. Measure capacity on the Galaxy Tab, representative phones, and HUB; base rollout limits on observations, not hypothetical billion-node claims. Keep optional diagnostics local or explicitly exported; do not add hidden analytics.

Each phase report must state files changed, behaviour changed, meaningful validation performed, compatibility implications, and remaining gaps. Do not mark subsequent phases complete because interfaces or stubs exist.

## 11. Required acceptance scenarios

| Scenario | Required result |
| --- | --- |
| A creator publishes; B mirrors; A offline; C joins through B | C discovers A, follows locally, reads verified cached public content; direct contact can remain pending |
| Official NoSlop node offline after earlier successful sync | Client tries persisted alternatives; no dependency on contacting the official node first |
| Completely fresh client with no reachable seed/invite | Clear recoverable onboarding state, no false claim that gossip can discover peers from nothing |
| Creator A connects to creator D | Permitted discovery metadata exchanged; no automatic foreign post/media replication |
| Mirror substitutes payment URL, author, bytes, or profile fields | Signature/hash verification fails or unsigned fields are ignored; original signed destination remains authoritative |
| Mirror replays an older creator card after a newer one was seen | Rollback rejected; offline/new-client freshness limitations remain explicit |
| Repeated relayed announcement from an offline creator | No false direct-online status and no unbounded freshness extension |
| Followed creator's discovery route expires | Subscription, personal messages, and saved content survive |
| Forged/expired mirror notices and malicious oversized discovery batches | Bounded rejection, no trust promotion, no uncontrolled cache/queue growth |
| Per-creator and total upload/storage limits reached | Limits enforced together; UI explains paused/deferred work |
| Mirror switches to creator role | Hosting withdrawal occurs; subscriptions retained; no new community mirroring |
| Private post/DM/group appears in a public fetch/relay path | Rejected; private data never served as public mirrored content |
| Creator changes mirror permission or deletes public post | Honest online mirrors process signed update/tombstone; offline recall limits documented |
| HUB handover interrupted at each stage | Recoverable state; no silent key loss, conflicting revision writers, or competing service ownership |
| Phone fully offline after successful HUB handover | Supported node duties continue within configured budgets |
| Older peer participates | Explicit compatible subset or clear unsupported capability; no weakened authentication fallback |

Use unit tests for policy/record validation and migration boundaries, integration tests for protocol paths, and actual multi-device/HUB tests for reachability and lifecycle claims. Simulated scale tests cannot alone prove Tor or Android device capacity.

## 12. Decisions to settle during development

Do not silently treat these as already agreed:

1. Public permission for any mirror versus individually approved mirrors, and defaults for existing creators.
2. Numeric retention/expiry periods, quotas, discovery fan-out, connection limits, and retry budgets. Choose conservative measured defaults; the old 15/30-minute behaviour is evidence, not the target.
3. Signature/canonical serialization format, revision ordering, legacy-post interoperability, and signed deletion policy.
4. Freshness UX for cached payment links and expired cards; immediate latest-value guarantees are impossible during isolation.
5. Home HUB same-endpoint takeover versus a distinct signed endpoint; control/signing authority, coordination and rollback.
6. Whether multiple independent logical node roles may run on one HUB later. Initial implementation must preserve the non-creator-only community mirroring rule.
7. Creator public-key/burnable identity rotation and continuity: do not automatically link identities the user intended to sever.
8. How users obtain an alternate bootstrap invitation before any default-node connection.

## 13. Non-goals and future promotion evidence

Not in scope: MVP/iOS migration, rewriting the feed, removing the feed API proxy, copying creator keys to community phones, fully delegated publishing, a global authoritative directory, guaranteed latest metadata while offline, or provable legal copyright ownership.

After implementation, useful demonstrations for the promotion strategy are: a creator offline while their work remains accessible; the default node switched off while known peers continue; visible opt-in phone budgets; a HUB keeping service running while the phone is off. Present these as measured demonstrations only after passing the corresponding acceptance tests. The enhancement discussion must not become a reason to postpone all small-scale tester recruitment indefinitely.

## 14. HAI-Net source review and required integration adjustments

This section supersedes any assumption that the HUB already provides full Android-equivalent processing or safe active takeover. Findings are based on inspected source and targeted searches, not a complete security audit. File paths below are relative to `hai-master`.

### 14.1 Existing building blocks worth retaining

- `hainet-seed/src/lib.rs` contains configuration/identity import machinery for owner-controlled deployment. `hainet-seed/src/installer/deployment.rs` configures Tor ports 8080 and 9999 on the service. Android already has an SSH deployment counterpart.
- `hainet-core/src/main.rs` contains a portal/API listener, owner-key QR-signature verification, a social mesh TCP listener, and SQLite setup for posts, DMs, and peers.
- `hainet-core/src/api_router.rs` implements command-style peer, DM, and packet synchronisation through `/api/invoke`.
- `hainet-social` contains packets, identity/crypto, gossip/firewall, deduplication, media/download/congestion, feed, group, and messaging modules. Audit which are on the actual production path; module existence does not establish enforcement.
- `hainet-core/src/storage/cas.rs` provides BLAKE3-addressed local content storage. Other storage/networking modules contain partial implementations and explicitly simulated or TODO paths; do not infer working distributed storage from the local store.
- `hainet-core/src/admin_bridge.rs`, `hainet-persona`, and MCP server crates provide substantial local-AI/tool orchestration code. Keep it modular and optional for the NoSlop hosting milestone.
- `hainet-collab/src/policy.rs` has a compute resource-policy model that may inform shared UX, but is not an existing per-creator mirror quota implementation.

### 14.2 Integration blockers and completion gates

| Finding from supplied source | Required work | Completion evidence |
| --- | --- | --- |
| `main.rs` API binds to `0.0.0.0`; `/api/invoke` exempts command prefixes `sync_`, `get_dms`, and `get_mesh_peers` from its login check | Authenticate and authorise every data/control command. Use explicit capabilities, not command-prefix exemptions. Network reachability or Tor transport alone must not count as owner authentication | An unauthorised LAN/onion client cannot read DMs, replace peers, push packets, or drain the inbox |
| API login uses a process-wide `IS_LOGGED_IN` flag; QR authentication sets it globally | Replace global login state with per-client scoped, expiring, revocable sessions or an equivalent authenticated request protocol. Bind QR challenges to the requesting session and prevent replay | Logging in one browser/phone does not authorise another client; expired/replayed challenges fail |
| Active TCP listener reads JSON lines into a growing string, spawns per-connection tasks, and buffers incoming packets in a `Vec` | Bound frame sizes, connections, timeouts, memory, per-sender work, and queues. Verify signatures and protocol state before trusted processing or expensive actions | Oversized/slow/replayed/forged traffic has bounded effects; a claimed trusted sender ID does not bypass verification |
| Listener directly buffers `MESSAGE` traffic and uses sender-ID database checks for other packet classes; some handshake fields update peer routes before visible signature verification in that path | Consolidate ingress through a verified admission pipeline. Preserve author signature and authenticated routing context. Audit the admin-DM branch as well | Invalid packets cannot rewrite peer endpoints, acquire trust, or invoke owner AI/tool authority |
| `sync_pull_packets` copies and clears the in-memory buffer before client acknowledgement | Add a durable inbox/outbox, stable IDs, bounded batches, cursors/ACKs, idempotent replay, and transactionally safe deletion after acknowledgement | Restart and interrupted response do not lose pending packets; repeated pulls do not duplicate user-visible state |
| SQLite schema shown is a small subset of Android's model, with ad hoc ignored ALTER errors | Introduce versioned migrations and an explicit sync data model. Avoid claiming Room schema parity | Upgrade/restart tests preserve identity, pending delivery, relationships, and signed provenance |
| Rust `hainet-social/src/crypto.rs` derives the message key with SHA3-256(shared secret), while Android DM v2 uses directional HKDF-SHA256 and authenticated metadata | Implement explicit versioned interoperability, including exact encodings, AAD, directional key binding, replay checks, and delivery ACKs. Do not downgrade Android to older Rust semantics | Shared Kotlin/Rust vectors verify both directions, tamper rejection, wrong identity/direction rejection, and ACK behaviour |
| Rust packet definitions retain older connection and encrypted-payload shapes | Build a packet-by-packet compatibility matrix for the actual Android release; include handshake nonce/state, groups, discovery, media hash, timestamps, and framing | Cross-language fixtures and multi-device tests pass for every advertised capability |
| Android backup worker calls `/api/backup/push`; targeted searches of supplied Rust source did not find that route, `/api/backup/pull`, or studio queue/publish routes | Resolve endpoint mismatch: implement and test the required API, or migrate both ends together with compatibility handling. Do not equate an old document's endpoint list with implemented routes | Real encrypted backup upload, download, restore, and error handling succeed between Android and HUB |
| Tor deployment advertises API and mesh ports on one onion, while older integration text also proposes a separate client-authenticated API onion | Select and document the actual topology. Independently enforce application authentication. Verify client-auth configuration if chosen rather than assuming it exists | Unauthorised clients are rejected on both LAN and Tor; owner access survives restart |

For the raw HTTP implementation, review parsing and request/body limits. Prefer a maintained HTTP stack already compatible with the project over expanding custom parsing. An authenticated control plane is a prerequisite for changing trust lists, handing over identity, or operating local AI tools.

### 14.3 Identity and provenance conflicts in the older plan

`docs/NOSLOP_INTEGRATION_PLAN.md` proposes a blockchain transaction linking the personal identity to the creator channel. This conflicts with the Android design goal of a severable/burnable public identity. Do not make that link mandatory, automatic, or a prerequisite for creator badges or mirroring. Explicit optional public identity linkage would need a separate product decision and clear disclosure that it may be permanent.

Creator-signed records and content hashes are sufficient building blocks for the authorship verification in this enhancement; no blockchain dependency is required by the agreed scope. Preserve the distinction between key-level provenance and legal copyright ownership.

Audit owner-HUB import against the exact Android key formats, including current expanded/seed encodings, Ed25519/X25519 public-key consistency, onion derivation, personal versus creator identity, and backup recovery. Older PKCS#8 assumptions must not truncate or reinterpret newer formats. Validate with shared vectors before importing live identities. Ensure secret-bearing temporary configuration files are permission-restricted and cleaned up; do not include secrets in logs.

### 14.4 Content addressing and storage migration

HAI-Net's local CAS uses BLAKE3 while Android's current mesh media integrity work uses SHA-256. Treat these as different named digests. A HUB may retain BLAKE3 as its internal storage key while also verifying/storing the creator-bound SHA-256 digest; it must not replace a signed hash with another algorithm's result. Define an algorithm-tagged wire format or explicit mapping, and test wrong-algorithm and wrong-content cases.

Store original signed creator/post envelopes alongside any portal-friendly projections. The simplified `posts` table containing author/content alone must not become the authoritative source for re-exported mirrored publications. Rebuilding a projection must not erase signatures, permissions, donation provenance, or tombstones.

### 14.5 Deployment and operational model

Provide a minimal **NoSlop HUB** service profile: authenticated controller API, Tor/social transport, durable storage/sync, and the selected creator or mirror role. Local AI, transcription, generation, community compute, and chain features should be optional additions rather than prerequisites for keeping a node online. Service isolation and resource budgets should ensure an AI workload cannot starve message delivery or discovery.

Use immutable tested revisions or release artifacts in deployment. Inspect both the Android deployer and seed installer so a moving repository branch cannot silently change the HUB protocol beneath an installed phone. Include capability/version negotiation, migration backup, health checks, and a documented rollback path.

Distinguish the internal owner-device coordination network from the public Tor social network. Existing libp2p/discovery/storage components must not accidentally publish personal LAN details or owner-only service endpoints into public creator discovery.

Keep Home HUB phase 5, but require phase 0H first. The immediate milestone is a reliable owner-controlled backend for the existing Android app; completing the entire HAI-Net vision is not a dependency.

### 14.6 Added cross-repository acceptance scenarios

1. Unauthenticated API requests fail before and after another client logs in; sync commands have no exemption.
2. HUB crashes after packet receipt, during pull, and before/after ACK: delivery resumes without losing acknowledged state or duplicating posts.
3. Android DM v2, nonce-bound handshake, and media-hash fixtures interoperate with Rust; modified metadata and wrong direction fail verification.
4. Actual Android backup worker uploads to a real supported route; downloaded archive restores on a test device with identity consistency checks.
5. Mirrored public posts survive HUB restart with original signatures, creator attribution, donation references, and media hashes intact.
6. Personal/creator keys remain separate through deploy, discovery, public posting, and takeover; no mandatory chain identity link is emitted.
7. Older HUB/Android versions negotiate a supported subset or fail clearly without silent security downgrade.
8. Minimal HUB operates without an LLM or media-generation stack installed; optional heavy processing cannot exhaust hosting budgets.

## 15. Revision 2 summary for the next LLM

The proposed product architecture is unchanged. What changed is the implementation order and confidence: HAI-Net supplies useful deployment, social, storage, and AI building blocks, but the attached revision is not yet a drop-in backend for the newer Android app. Start with authenticated control, verified ingress, durable delivery, and protocol parity. Then add discovery/mirroring and coordinated HUB takeover. Preserve existing functional code while migrating it; do not propagate old completion claims into new status reports without end-to-end evidence.
