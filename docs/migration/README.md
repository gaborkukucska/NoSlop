# NoSlop Cross-Platform Migration — Project Hub

This folder is the **single source of truth** for the migration of NoSlop from a native-Android app to a cross-platform app (iOS-first, then desktop, then Android parity), built on **Kotlin Multiplatform + Compose Multiplatform** and optimized for **long-term AI-assisted maintainability**.

> **If you are picking this up cold (human or AI): start here, then read `PROGRESS_LOG.md` for the latest state.**

## Documents in this folder

| Document | Purpose | Read when |
|---|---|---|
| [`MIGRATION_PLAN.md`](MIGRATION_PLAN.md) | Master plan: vision, the 6 phases, and the live status board | First — the map of the whole project |
| [`STRATEGY.md`](STRATEGY.md) | *Why* KMP, why iOS-first, the iOS constraints, options we rejected | When you need the rationale behind a decision |
| [`PHASE_0.md`](PHASE_0.md) | Detailed stages + todo checklist for the current phase | When doing the work |
| [`DECOMPOSITION_MAP.md`](DECOMPOSITION_MAP.md) | Concrete plan for splitting the monolith files | During Phase 0 refactoring |
| [`DECISIONS.md`](DECISIONS.md) | Architecture Decision Records (ADRs) — every binding choice + its reasoning | Before re-litigating a decision |
| [`PROGRESS_LOG.md`](PROGRESS_LOG.md) | Reverse-chronological journal of what was done each session | **Every time you resume** |

## How to use this hub (the working loop)

1. **Resume:** read the top of `PROGRESS_LOG.md` (latest entry) and the status board in `MIGRATION_PLAN.md`.
2. **Pick work:** take the next unchecked `[ ]` todo in the active phase doc (e.g. `PHASE_0.md`).
3. **Do it:** implement, keeping the documentation standard (see below). Check the box when done.
4. **Record:** add a dated entry to `PROGRESS_LOG.md` describing what changed and what's next. Update the status board if a stage completed.
5. **Decide:** if you made a binding architectural choice, add an ADR to `DECISIONS.md`.
6. **Commit:** small, focused commits on the `feat/cross-platform-migration` branch.

## Code documentation standard (non-negotiable, for resumability + AI-maintainability)

Every new or refactored file must have:
- A **file-level KDoc** header: what this file is, its responsibility, and how it fits the architecture.
- **KDoc on every public symbol** (class, function, property): purpose, params, return, and any non-obvious behavior.
- **`// WHY:` comments** for any non-obvious decision (security, protocol, platform quirk). Future maintainers — and AI — must understand *why*, not just *what*.
- **No file over ~300 lines** and **one responsibility per file** (see `DECOMPOSITION_MAP.md` for the rationale).

## Branch & repo facts

- **Working clone:** `~/Documents/NoSlop-xplatform`
- **Branch:** `feat/cross-platform-migration` (off `main`)
- **Remotes:** `origin` = `kufton/NoSlop` (your fork — push here) · `upstream` = `gaborkukucska/NoSlop` (Gabor's repo — fetch to stay current)
- **Merge path:** push to the fork → when Gabor approves, open a PR `kufton:<branch>` → `gaborkukucska:main`; Gabor merges. Prefer small phase-scoped PRs (ADR-006). Sync regularly: `git fetch upstream && git rebase upstream/main`.
