# SSH Deployment Bug: Persistent `sudo` Shim Shadows System `sudo`

**File affected:** `NoSlop-Legacy-Android/app/src/main/java/com/noslop/app/net/SshDeployer.kt`
**Severity:** High — breaks `sudo` on every target host after deployment; also a latent privilege-escalation risk

---

## Summary

The deployment script that `SshDeployer.kt` pipes over SSH creates a fake `sudo`
binary at `~/.cargo/bin/sudo` on the target machine so that non-interactive build
steps (`cargo build`, `npm install`, etc.) can transparently supply the root
password without prompting. This shim is **never removed** after deployment
finishes. Because `~/.cargo/bin` is prepended to `PATH` (both by rustup and by
the script itself), it permanently shadows the real `/usr/bin/sudo` in every
future login shell on that machine.

In a fresh shell, the environment variable the shim depends on (`$SUDO_PASS`)
no longer exists, so the shim pipes an **empty password** into the real sudo:

```bash
echo "$SUDO_PASS" | /usr/bin/sudo -S -p "" "$@"
```

This produces exactly the symptom observed on a live deployment target:

```
Sorry, try again.
sudo: no password was provided
sudo: 1 incorrect password attempt
```

The user is left with a broken `sudo` on their own machine until they manually
discover and delete the shim.

---

## Root Cause

**Location:** `SshDeployer.kt`, inside the deployment script string.

```bash
mkdir -p "$HOME/.cargo/bin"
cat << 'EOF_SUDO' > "$HOME/.cargo/bin/sudo"
#!/bin/bash
if [ "$(id -u)" -eq 0 ]; then
    exec /usr/bin/sudo "$@"
else
    echo "$SUDO_PASS" | /usr/bin/sudo -S -p "" "$@"
fi
EOF_SUDO
chmod +x "$HOME/.cargo/bin/sudo"
```

Combined with four separate `export PATH="$HOME/.cargo/bin:$PATH"` lines
later in the same script, this shim binary sits ahead of the real `sudo` on
`PATH` for the rest of the session — and forever afterward, since nothing
deletes it.

There is no corresponding `rm` of `$HOME/.cargo/bin/sudo` anywhere in the
script, including on the early-exit paths (`FULL_WIPE`, `RESET_IDENTITY`,
`UPDATE_HUB`, and installer-failure exits).

### Why it's dangerous, not just annoying

Beyond breaking the user's shell, this pattern plants a **silent, persistent
password relay disguised as a system binary**. For as long as the shim exists
and `~/.cargo/bin` precedes `/usr/bin` on `PATH`:

- Any script, cron job, or other software on the box that invokes `sudo`
  by name will silently be intercepted by this shim.
- While `$SUDO_PASS` was set (during the original deploy session), any such
  invocation would have succeeded with root access with no prompt and no
  indication to the user that non-interactive root execution occurred.
- Even after `$SUDO_PASS` expires, the shim remains a landmine: it fails
  loudly (as observed) but could be reintroduced or reactivated by anything
  that re-sets `$SUDO_PASS` in that shell.

This is a bigger concern than the login breakage itself and is worth treating
as a security defect, not just a UX bug.

---

## Suggested Fixes

### 1. Always remove the shim, including on failure paths
Add a trap immediately after creating it so it is deleted no matter how the
script exits:

```bash
trap 'rm -f "$HOME/.cargo/bin/sudo"' EXIT
```

`trap ... EXIT` fires on normal completion, `exit N`, and (under `set -e`)
most error exits — this alone would have prevented the reported incident.

### 2. Avoid `PATH`-shadowing entirely — use `SUDO_ASKPASS`
Rather than impersonating the `sudo` binary itself, use sudo's built-in
non-interactive password mechanism, which achieves the same goal without ever
touching the name `sudo` on `PATH`:

```bash
cat << 'EOF' > "$HOME/.hainet_askpass"
#!/bin/bash
echo "$SUDO_PASS"
EOF
chmod +x "$HOME/.hainet_askpass"
trap 'rm -f "$HOME/.hainet_askpass"' EXIT

run_sudo() {
    SUDO_ASKPASS="$HOME/.hainet_askpass" sudo -A "$@"
}
```

This removes the shadowing risk structurally — there is no longer a fake
`sudo` binary anywhere on disk, so there is nothing to forget to clean up and
nothing for other processes to be silently intercepted by.

### 3. Reconsider whether the shim is even necessary
Check whether the subprocesses invoked during deployment (`cargo build`,
`npm install`, etc.) actually call `sudo` themselves. If they don't, the
top-level `run_sudo()` helper already used throughout the script may be
sufficient on its own, and the shim can potentially be removed entirely
rather than just fixed.

### 4. Add a self-healing guard for already-affected machines
Since this bug has likely already hit other users/devices (as it did here),
consider having the app check for and remove a stray
`~/.cargo/bin/sudo` at the start of any future SSH session to a known host,
so previously-broken machines get repaired automatically on next contact
rather than requiring manual cleanup.

---

## Secondary Observation: Duplicate `PATH` Entries

The affected user's `$PATH` contained `/home/tom/.local/bin` three times and
`/home/tom/.cargo/bin` once, in an order suggesting multiple appends across
separate deploy runs or rustup installer invocations. This is very likely a
side effect of the same deployment flow re-running `curl ... | sh` (rustup)
and/or shell-rc modifications without idempotency checks. Not harmful on its
own, but worth deduplicating for cleanliness — e.g. checking shell rc files
for existing `PATH` export lines before appending new ones.

---

## Reproduction Trail (for reference)

1. `sudo apt update` on the target machine failed with
   `Authentication required but not attempted`.
2. Traced to Ubuntu 25.10+ defaulting `sudo` to `sudo-rs` via
   `update-alternatives`, initially suspected as the cause.
3. Switching the alternative back to classic `sudo.ws` appeared to fix it,
   but the same failure recurred in a new terminal session.
4. `type sudo` revealed the real `sudo` in use was
   `/home/tom/.cargo/bin/sudo` — a shell script, not the system binary —
   which had been silently shadowing `/usr/bin/sudo` via `PATH` order all
   along.
5. Source of that file traced to `SshDeployer.kt`'s deployment script, which
   creates it during every HAI-Net hub deployment and never removes it.
