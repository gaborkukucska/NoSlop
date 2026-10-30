# Handoff for Gabor: merging the iOS / desktop port

## What the merge brings in

Branch `ios-port-for-upstream` on `kufton/NoSlop-fork` (a GitHub fork of your repo) already contains your `main` up to 0.7.4-alpha (`a29c942`).
Merging it into your `main` therefore has **no conflicts**: git just fast-forwards.

It changes only these:
- `mvp/`: the Kotlin Multiplatform app. That means the iOS app, the desktop hub, and the bridge to Android.
- `docs/`: the migration notes and this file.

**`app/` is byte-identical to your `main`.** Nothing in the Android app changes.

`mvp/` now speaks your protocol v2 from 0.7.1-alpha:
- the E2 envelope on every packet;
- D07 v2 handshakes with a nonce and a target;
- DM v2 (C08/D05) with DM_ACK v3;
- the canonical `encodeForSigning` forms for posts, comments, votes and reactions;
- the media `sha256`.

It was checked in both directions:
- `mvp/composeApp/src/jvmTest/.../ProtocolV2Test.kt` verifies vectors that your own `CryptoService`, `EnvelopeAuth` and `MeshPacketVerifier` produced. They are in `jvmTest/resources/android-v2-vectors.json`.
- Your verifiers accepted every packet type the KMP side sends.

## How to merge

**Option A: the GitHub PR (easiest).** Open the PR from `kufton/NoSlop-fork:ios-port-for-upstream` into `main`, then click **"Create a merge commit"**.
- Please don't squash. A squash rewrites our history into one commit. Then the next time we sync your `main`, git cannot tell what is already merged, and the same files conflict again.

**Option B: the command line.**
```bash
git remote add kufton https://github.com/kufton/NoSlop-fork.git   # once
git fetch kufton
git checkout main
git merge --ff-only kufton/ios-port-for-upstream          # fast-forward; fails loudly if main moved on
git push origin main
```
`--ff-only` refusing to merge means you committed to `main` after we synced. In that case:
- tell us and we re-sync; or
- run `git merge kufton/ios-port-for-upstream` without the flag. Any conflict can only be in `mvp/` or `docs/`, so for `app/` keep your side.

## Keeping it easy from here

- **Ownership.** You own `app/` and we own `mvp/`. We merge your `main` into our branch before every handoff, so you always receive a fast-forward.
- **Protocol changes.** Wire changes are the one thing that breaks the iOS side silently: packets get dropped with no error on either phone. A one-line heads-up in the commit message works, for example `wire:` or `protocol:`. Even better, regenerate the vectors (below) and our `jvmTest` catches the break before anyone's phone does.
- **Regenerating the vectors.** `android-v2-vectors.json` is built by a small unit test inside `app/` from deterministic seeds. We can add that test to `app/src/test` if you're happy to carry it. It touches no production code.
- **Building `mvp/`.**
  - `cd mvp && ./gradlew :composeApp:jvmTest` runs the tests.
  - `./gradlew :composeApp:runBridge --args="9876"` starts the desktop bridge.
  - The Gradle wrapper jar is now committed, so `./gradlew` works on a fresh clone.
  - The iOS app needs Xcode: `mvp/iosApp`.
