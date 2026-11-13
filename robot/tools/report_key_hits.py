#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# RP5: which entries of an APK carry the debug build's throwaway report key (src/debug ReportTestKey): its base64, its
# 33 bytes, its fingerprint, or the class itself (its dex type descriptor or source file name). verify_apk.sh runs it on
# the release APK; ReportKeysTest runs it on made-up zips. Main code's ReportKeys looks the class up by its dotted name,
# so that name is in every release dex and isn't a needle: the class and the key are.
# Usage: report_key_hits.py APK ReportTestKey.kt. Prints "entry(kind)" per hit, nothing when clean; exit 2: no key read.
import base64, hashlib, re, sys, zipfile


def fingerprint(key):
    """The card's fingerprint of [key]'s 33 bytes: SHA-256's first 10 bytes in groups of 4 hex digits."""
    h = hashlib.sha256(key).hexdigest()[:20]
    return " ".join(h[i:i + 4] for i in range(0, 20, 4))

def needles(src):
    m = re.search(r'"([A-Za-z0-9+/]{43}=|[A-Za-z0-9+/]{44})"', src)
    if not m:
        return None
    k = m.group(1)
    raw = base64.b64decode(k + "=" * (-len(k) % 4))
    fp = fingerprint(raw)
    return {
        "base64": k.encode(),
        "bytes": raw,
        "fingerprint": fp.encode(),
        "fingerprint hex": fp.replace(" ", "").encode(),
        "class": b"Lapp/heyrobot/sys/ReportTestKey;",
        "source file": b"ReportTestKey.kt",
    }

def main(apk, keyfile):
    try:
        found = needles(open(keyfile, encoding="utf-8").read())
    except OSError:
        found = None
    if not found:
        print(f"no key read from {keyfile}")
        return 2
    hits = []
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            d = z.read(n)
            hits += [f"{n}({kind})" for kind, x in found.items() if x in d]
    print(" ".join(hits))
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
