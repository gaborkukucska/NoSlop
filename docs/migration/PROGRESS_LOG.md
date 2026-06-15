# Progress Log

Reverse-chronological journal. **Newest entry on top.** Read the top entry first when resuming.

---

## 2026-06-16 — Draft PR opened upstream

- Opened **draft PR [gaborkukucska/NoSlop#1](https://github.com/gaborkukucska/NoSlop/pull/1)** (`kufton:feat/cross-platform-migration` → `gaborkukucska:main`), docs-only, so Gabor can review the plan before code lands. Marked DRAFT/WIP.
- Body summarizes approach (KMP, iOS leaf + Home HUB, Phase-0-first, small phase-scoped PRs) and points to the key docs.
- **Next:** baseline build result, then Stage 0.2 tests.

---

## 2026-06-16 — Fork created, branch pushed, remotes wired

**Done:**
- Forked `gaborkukucska/NoSlop` → **`kufton/NoSlop`**.
- Reconfigured remotes: `origin` = fork (push), `upstream` = Gabor's repo (fetch/sync).
- Pushed `feat/cross-platform-migration` to the fork → **work is now backed up to a remote.**
- Updated ADR-003 (fork→PR workflow) and added ADR-006 (small phase-scoped PRs); updated README repo facts.

**Merge plan (agreed):** push to fork; when Gabor is happy, open PR → `gaborkukucska:main`; he merges (he holds write). Keep branch rebased on `upstream/main` since Gabor is actively developing.

**Next:** baseline build result (running), then Stage 0.2 golden-vector tests.

---

## 2026-06-16 — Project kickoff & Phase 0 setup

**Done:**
- Established the migration approach (KMP + Compose Multiplatform; see `STRATEGY.md` and ADR-001).
- Full clone of `gaborkukucska/NoSlop` → `~/Documents/NoSlop-xplatform` (full history for archaeology).
- Created branch `feat/cross-platform-migration` off `main` (HEAD `09d2b3f "still working on video playback"`).
- Scaffolded the migration documentation hub under `docs/migration/`: `README.md`, `MIGRATION_PLAN.md`, `STRATEGY.md`, `PHASE_0.md`, `DECOMPOSITION_MAP.md`, `DECISIONS.md`, and this log.
- Mapped the internal structure of the six monolith files and produced a grounded, behavior-preserving decomposition plan (`DECOMPOSITION_MAP.md`).
- Recorded 5 ADRs covering the stack, the iOS leaf-node model, branch/fork workflow, Phase-0-first ordering, and the wire-protocol contract.

**Verified facts about the environment:**
- GitHub account `kufton` has **READ-only** access to the upstream repo; **no fork exists** → pushing requires a fork or write access (ADR-003). Work is **local-only** for now.
- `~/Documents/noslop` (lowercase) and the session worktree belong to an unrelated home-spanning git repo (racing-sim/marketing history) — **not** the NoSlop app. The canonical working copy is `~/Documents/NoSlop-xplatform`.

**Open questions for the user/owner:**
1. **Push strategy:** fork to `kufton/NoSlop` now, or request write access to upstream? (Blocks remote backup — see ADR-003.)
2. Build environment: is the Android SDK + a JDK installed here so we can establish a `./gradlew assembleDebug` baseline? (Stage 0.1)

**Next up (Stage 0.1 → 0.2):**
1. Commit the scaffold to the branch.
2. Confirm/record the build baseline (or note tooling gaps).
3. Begin Stage 0.2 — golden-vector tests for `CryptoService`, `MnemonicGenerator`, and `Packets`/`NetworkPacket` **before** any refactoring.

**Notes:**
- Decompose-order is deliberately lowest-risk-first: data repo → packet handler → view-model → UI screens. Tests must pass after each extraction.
- The previously-discussed standalone assessment doc was written outside this repo; its content now lives in `STRATEGY.md` + `DECISIONS.md` inside the project, which is the durable home.
