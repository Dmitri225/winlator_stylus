#!/usr/bin/env python3
"""Compare every entry of two APKs by SHA-256 of its uncompressed content.

Usage: compare_apks.py OURS.apk OFFICIAL.apk REPORT.md
Entries expected to differ: Java bytecode, compiled resources/manifest,
signatures, and native libraries compiled from source in this build.
Everything else (prebuilt .so, rootfs, Wine/Box64/DXVK/driver archives)
must be byte-identical to the official release.
"""
import hashlib, sys, zipfile, fnmatch

ours, official, report = sys.argv[1:4]

EXPECTED_DIFF = [
    "classes*.dex", "resources.arsc", "AndroidManifest.xml", "res/*",
    "META-INF/*", "kotlin/*", "*.kotlin_builtins", "DebugProbesKt.bin",
    "assets/dexopt/*",
]

def digests(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            h = hashlib.sha256()
            with z.open(info) as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            out[info.filename] = (h.hexdigest(), info.file_size)
    return out

a, b = digests(ours), digests(official)
expected = lambda n: any(fnmatch.fnmatch(n, p) for p in EXPECTED_DIFF)

same, diff_expected, diff_unexpected = [], [], []
for name in sorted(set(a) & set(b)):
    if a[name][0] == b[name][0]:
        same.append(name)
    elif expected(name):
        diff_expected.append(name)
    else:
        diff_unexpected.append(name)
only_ours = sorted(n for n in set(a) - set(b))
only_off = sorted(n for n in set(b) - set(a))
only_ours_unexpected = [n for n in only_ours if not expected(n)]

def size(d):
    return sum(v[1] for v in d.values())

lines = [
    "# APK comparison: this build vs official Winlator 11.2",
    "",
    f"- Entries in this build: {len(a)} ({size(a)/1e6:.1f} MB uncompressed)",
    f"- Entries in official:   {len(b)} ({size(b)/1e6:.1f} MB uncompressed)",
    f"- Byte-identical: **{len(same)}** "
    f"({sum(a[n][1] for n in same)/1e6:.1f} MB)",
    f"- Different, expected (code/resources/signature): {len(diff_expected)}",
    f"- Different, NOT in the expected list: **{len(diff_unexpected)}**",
    f"- Only in this build: {len(only_ours)} (unexpected: {len(only_ours_unexpected)})",
    f"- Only in official: {len(only_off)}",
    "",
]
def section(title, names, src):
    if not names:
        return
    lines.append(f"## {title}")
    for n in names:
        lines.append(f"- `{n}` {src[n][1]} bytes sha256 {src[n][0][:16]}…")
    lines.append("")
section("Different, not in the expected list (compiled-from-source native libs are normal here)", diff_unexpected, a)
section("Only in this build", only_ours, a)
section("Only in official", only_off, b)
section("Different, expected", diff_expected, a)

open(report, "w").write("\n".join(lines))
print("\n".join(lines[:12]))
for n in diff_unexpected + only_ours_unexpected:
    print("CHECK:", n)
