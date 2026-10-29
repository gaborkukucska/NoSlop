#!/usr/bin/env python3
"""
pull_noslop_crash.py - collect crash evidence for NoSlop from a USB-connected Android device.

Writes to your Desktop:
  noslop_crash_report_<timestamp>.txt   <- the one to drop into chat
  noslop_logcat_full_<timestamp>.txt    <- full logcat dump, only if asked for

What the report contains:
  1. Device + installed app version/flags
  2. ApplicationExitInfo history (Android 11+): WHY each recent process death happened
     (CRASH, CRASH_NATIVE, ANR, LOW_MEMORY, EXCESSIVE_RESOURCE_USAGE, SIGNALED, ...)
  3. DropBox crash/ANR/tombstone entries for NoSlop (persist across logcat rotation)
  4. logcat crash buffer
  5. logcat lines from NoSlop's own processes + crash-related system tags
  6. Tail of NoSlop's own noslop-debug.log (only works on debuggable builds)

Usage:
  python3 pull_noslop_crash.py                 snapshot of what's already on the phone
  python3 pull_noslop_crash.py --live          snapshot, then clear logcat, launch NoSlop and
                                               record until it crashes (or Ctrl+C)
  python3 pull_noslop_crash.py --full-logcat   also save the unfiltered logcat dump
  python3 pull_noslop_crash.py --bugreport     also save a full `adb bugreport` zip (slow,
                                               includes native tombstones)
Options:
  --serial SERIAL   device serial (default: the only connected device)
  --package PKG     default com.noslop.app
  --out DIR         output dir (default ~/Desktop, falls back to current dir)
  --timeout MIN     --live gives up after this many minutes (default 15)
"""
import argparse
import datetime
import re
import subprocess
import sys
import time
from pathlib import Path

DEFAULT_PKG = "com.noslop.app"
CRASH_TAGS = (
    "AndroidRuntime", "DEBUG", "libc", "tombstoned", "crash_dump64", "crash_dump32",
    "lowmemorykiller", "lmkd", "ActivityManager", "ActivityTaskManager", "ANRManager",
)
LIVE_TRIGGERS = (
    "FATAL EXCEPTION",
    "Fatal signal",
    "ANR in {pkg}",
    "Process {pkg} (pid",          # "Process com.noslop.app (pid 1234) has died"
    "Killing {pkg_pidless}",       # ActivityManager kill lines
)
SEP = "=" * 100


# ---------------------------------------------------------------- adb helpers
def adb(args, serial, timeout=180):
    cmd = ["adb"] + (["-s", serial] if serial else []) + args
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    except FileNotFoundError:
        sys.exit("ERROR: `adb` not found on PATH.")
    except subprocess.TimeoutExpired:
        return 124, "", f"timed out after {timeout}s"
    return (r.returncode,
            r.stdout.decode("utf-8", "replace"),
            r.stderr.decode("utf-8", "replace"))


def shell(cmd, serial, timeout=180):
    return adb(["shell", cmd], serial, timeout)


def pick_device(serial):
    rc, out, err = adb(["devices"], None)
    if rc != 0:
        sys.exit(f"ERROR: adb devices failed: {err.strip()}")
    devs = [l.split("\t") for l in out.splitlines()[1:] if "\t" in l]
    ready = [d[0] for d in devs if d[1].strip() == "device"]
    if serial:
        if serial not in ready:
            sys.exit(f"ERROR: device {serial} not ready. adb devices says:\n{out}")
        return serial
    if not ready:
        sys.exit(f"ERROR: no device in 'device' state. adb devices says:\n{out}")
    if len(ready) > 1:
        sys.exit(f"ERROR: several devices connected, pass --serial. Found: {ready}")
    return ready[0]


# ---------------------------------------------------------------- collectors
def device_info(serial):
    props = ["ro.product.manufacturer", "ro.product.model", "ro.build.version.release",
             "ro.build.version.sdk", "ro.build.display.id", "ro.build.version.security_patch"]
    lines = []
    for p in props:
        _, out, _ = shell(f"getprop {p}", serial)
        lines.append(f"{p} = {out.strip()}")
    _, up, _ = shell("uptime", serial)
    lines.append(f"uptime = {up.strip()}")
    return "\n".join(lines)


def app_info(serial, pkg):
    rc, out, _ = shell(f"dumpsys package {pkg}", serial)
    if rc != 0 or f"Package [{pkg}]" not in out:
        return f"Package {pkg} not found on device!", False
    keep = re.compile(r"^\s*(versionName|versionCode|firstInstallTime|lastUpdateTime|"
                      r"flags|pkgFlags|privateFlags|targetSdk|minSdk|primaryCpuAbi|"
                      r"installerPackageName|signatures)=", re.I)
    picked, seen = [], set()
    for line in out.splitlines():
        if keep.search(line):
            s = line.strip()
            if s not in seen:
                seen.add(s)
                picked.append(s)
    debuggable = "DEBUGGABLE" in out
    picked.append(f"debuggable = {debuggable}")
    _, pid, _ = shell(f"pidof {pkg}", serial)
    picked.append(f"running pid(s) now = {pid.strip() or '(not running)'}")
    return "\n".join(picked), debuggable


def exit_info(serial, pkg):
    rc, out, err = shell(f"dumpsys activity exit-info {pkg}", serial)
    if rc != 0 or not out.strip():
        return f"(exit-info unavailable: {err.strip() or 'empty'})", []
    reasons = re.findall(r"reason=\d+ \(([A-Z_]+)\)\s+subreason=\d+ \(([A-Z_]+)\)", out)
    return out.strip(), reasons


def dropbox(serial, pkg):
    sections = []
    for tag in ("data_app_crash", "data_app_native_crash", "data_app_anr",
                "SYSTEM_TOMBSTONE", "data_app_strictmode"):
        rc, out, _ = shell(f"dumpsys dropbox --print {tag}", serial, timeout=240)
        if rc != 0 or not out.strip():
            continue
        # entries are separated by a line of '=' characters
        chunks = re.split(r"\n={20,}\n", out)
        hits = [c.strip() for c in chunks if pkg in c]
        if hits:
            sections.append(f"--- {tag}: {len(hits)} entr{'y' if len(hits) == 1 else 'ies'} "
                            f"for {pkg} ---\n" + f"\n{'-' * 60}\n".join(hits))
    return "\n\n".join(sections) if sections else "(no DropBox entries mentioning the package)"


def logcat_dump(serial):
    _, out, err = adb(["logcat", "-d", "-v", "threadtime", "-b", "main,system,crash,events"],
                      serial, timeout=300)
    return out or f"(logcat dump failed: {err.strip()})"


def crash_buffer(serial):
    _, out, _ = adb(["logcat", "-d", "-v", "threadtime", "-b", "crash"], serial)
    return out.strip() or "(crash buffer empty)"


THREADTIME = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(\d+)\s+(\d+)\s+\w\s+([^:]+?)\s*:")


def app_pids(full_log, pkg):
    pids = set()
    for m in re.finditer(r"Start proc (\d+):" + re.escape(pkg), full_log):
        pids.add(m.group(1))
    for m in re.finditer(r"am_proc_start: \[\d+,(\d+),\d+," + re.escape(pkg), full_log):
        pids.add(m.group(1))
    return pids


def filter_logcat(full_log, pkg, extra_pids):
    pids = app_pids(full_log, pkg) | set(extra_pids)
    out = []
    for line in full_log.splitlines():
        m = THREADTIME.match(line)
        if not m:
            if pkg in line:
                out.append(line)
            continue
        pid, _tid, tag = m.groups()
        if pid in pids or pkg in line:
            out.append(line)
        elif tag in CRASH_TAGS and (pkg in line or "FATAL" in line or "Fatal signal" in line
                                    or "lowmemorykiller" in tag or "lmkd" in tag):
            out.append(line)
    header = f"(app pids seen in log: {', '.join(sorted(pids)) or 'none found'})"
    return header + "\n" + ("\n".join(out) if out else "(no matching lines)")


def app_private_log(serial, pkg, debuggable, tail_lines=2000):
    if not debuggable:
        return ("(skipped: installed build is not debuggable, so `run-as` can't read "
                "files/noslop-debug.log. Use Settings > Structured Debug Logs > Copy Logs "
                "in the app instead, if it stays up long enough.)")
    rc, out, err = adb(["exec-out", "run-as", pkg, "cat", "files/noslop-debug.log"], serial)
    if rc != 0 or not out:
        return f"(could not read app log: {(err or out).strip()})"
    lines = out.splitlines()
    return "\n".join(lines[-tail_lines:])


def summarise(reasons, crash_buf, drop):
    s = []
    if reasons:
        s.append("Recent exit reasons (newest first): " +
                 ", ".join(f"{r}/{sr}" for r, sr in reasons[:15]))
    else:
        s.append("Recent exit reasons: none reported")
    blob = crash_buf + "\n" + drop
    exc = []
    for line in blob.splitlines():
        l = line.strip()
        if re.search(r"(FATAL EXCEPTION|Caused by:|^\S*(Exception|Error)[:\s]|"
                     r"E AndroidRuntime: [\w.$]+(Exception|Error)|Fatal signal|Abort message)", l):
            exc.append(l[:300])
    seen, uniq = set(), []
    for e in exc:
        key = re.sub(r"^\d\d-\d\d [\d:.]+\s+\d+\s+\d+\s+\w\s+", "", e)
        if key not in seen:
            seen.add(key)
            uniq.append(e)
    if uniq:
        s.append("Exception lines found:\n  " + "\n  ".join(uniq[:40]))
    else:
        s.append("No exception lines found in crash buffer / DropBox.")
    return "\n".join(s)


# ---------------------------------------------------------------- live mode
def live_capture(serial, pkg, timeout_min, out_path):
    print("[live] enlarging logcat buffers to 16M (resets on reboot)...")
    adb(["logcat", "-G", "16M"], serial)
    print("[live] clearing logcat (crash history was already saved in the snapshot)...")
    adb(["logcat", "-b", "main,system,crash,events", "-c"], serial)

    cmd = ["adb", "-s", serial, "logcat", "-v", "threadtime", "-b", "main,system,crash,events"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    print(f"[live] launching {pkg}...")
    shell(f"am force-stop {pkg}", serial)
    shell(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1", serial)

    triggers = [t.format(pkg=pkg, pkg_pidless=pkg) for t in LIVE_TRIGGERS]
    deadline = time.time() + timeout_min * 60
    stop_at = None
    lines = []
    print(f"[live] recording - use the app normally. Stops 8s after a crash, "
          f"after {timeout_min} min, or on Ctrl+C.")
    try:
        while True:
            raw = proc.stdout.readline()
            if not raw:
                break
            line = raw.decode("utf-8", "replace").rstrip("\n")
            lines.append(line)
            if stop_at is None and any(t in line for t in triggers) and (
                    pkg in line or "FATAL EXCEPTION" in line or "Fatal signal" in line):
                print(f"[live] crash signal seen: {line.strip()[:160]}")
                stop_at = time.time() + 8
            now = time.time()
            if (stop_at and now >= stop_at) or now >= deadline:
                break
    except KeyboardInterrupt:
        print("\n[live] stopped by you.")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
    out_path.write_text("\n".join(lines), encoding="utf-8")
    return "\n".join(lines), stop_at is not None


# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description="Collect NoSlop crash logs over adb.")
    ap.add_argument("--serial")
    ap.add_argument("--package", default=DEFAULT_PKG)
    ap.add_argument("--out")
    ap.add_argument("--live", action="store_true")
    ap.add_argument("--timeout", type=float, default=15.0)
    ap.add_argument("--full-logcat", action="store_true")
    ap.add_argument("--bugreport", action="store_true")
    a = ap.parse_args()

    serial = pick_device(a.serial)
    pkg = a.package
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = Path(a.out).expanduser() if a.out else Path.home() / "Desktop"
    if not out_dir.is_dir():
        out_dir = Path.cwd()
    report_path = out_dir / f"noslop_crash_report_{ts}.txt"
    full_path = out_dir / f"noslop_logcat_full_{ts}.txt"
    live_path = out_dir / f"noslop_logcat_live_{ts}.txt"

    print(f"Device {serial}, package {pkg}")
    print("[1/6] device + app info...")
    dev = device_info(serial)
    app, debuggable = app_info(serial, pkg)
    print("[2/6] exit-info...")
    exits, reasons = exit_info(serial, pkg)
    print("[3/6] DropBox crash entries...")
    drop = dropbox(serial, pkg)
    print("[4/6] logcat crash buffer...")
    cbuf = crash_buffer(serial)
    print("[5/6] logcat dump...")
    full = logcat_dump(serial)
    _, pidnow, _ = shell(f"pidof {pkg}", serial)
    filtered = filter_logcat(full, pkg, pidnow.split())
    print("[6/6] app private log...")
    privlog = app_private_log(serial, pkg, debuggable)

    if a.full_logcat:
        full_path.write_text(full, encoding="utf-8")

    live_section = ""
    if a.live:
        live_log, crashed = live_capture(serial, pkg, a.timeout, live_path)
        _, pidnow, _ = shell(f"pidof {pkg}", serial)
        live_filtered = filter_logcat(live_log, pkg, pidnow.split())
        # refresh exit-info / dropbox so the new crash is included
        exits, reasons = exit_info(serial, pkg)
        drop = dropbox(serial, pkg)
        cbuf = crash_buffer(serial)
        live_section = (f"{SEP}\nLIVE CAPTURE ({'crash captured' if crashed else 'no crash seen'}; "
                        f"full live log: {live_path.name})\n{SEP}\n{live_filtered}\n\n")

    if a.bugreport:
        bz = out_dir / f"noslop_bugreport_{ts}.zip"
        print("[extra] adb bugreport (takes a few minutes)...")
        rc, _, err = adb(["bugreport", str(bz)], serial, timeout=900)
        print(f"        {'saved ' + str(bz) if rc == 0 else 'failed: ' + err.strip()}")

    summary = summarise(reasons, cbuf, drop)
    report = (
        f"NoSlop crash report  {ts}\n\n"
        f"{SEP}\nSUMMARY\n{SEP}\n{summary}\n\n"
        f"{SEP}\nDEVICE\n{SEP}\n{dev}\n\n"
        f"{SEP}\nAPP\n{SEP}\n{app}\n\n"
        f"{SEP}\nEXIT INFO (dumpsys activity exit-info)\n{SEP}\n{exits}\n\n"
        f"{SEP}\nDROPBOX (crash / native crash / ANR / tombstone)\n{SEP}\n{drop}\n\n"
        f"{SEP}\nLOGCAT CRASH BUFFER\n{SEP}\n{cbuf}\n\n"
        f"{live_section}"
        f"{SEP}\nLOGCAT FILTERED (app pids + crash tags)\n{SEP}\n{filtered}\n\n"
        f"{SEP}\nAPP noslop-debug.log (tail)\n{SEP}\n{privlog}\n"
    )
    report_path.write_text(report, encoding="utf-8")

    print("\n" + summary)
    print(f"\nReport: {report_path}  ({report_path.stat().st_size // 1024} KB)")
    if a.full_logcat:
        print(f"Full logcat: {full_path}")
    if a.live:
        print(f"Live log: {live_path}")


if __name__ == "__main__":
    main()
