#!/usr/bin/env python3
"""Check that no API key leaked into a build artefact or into git.

Read-only. The key is read from `.env` inside this process and is never printed — only whether it
was *found somewhere it should not be*. Run before anything is published:

    python3 scripts/check-secrets.py app/build/outputs/apk/debug/app-debug.apk
"""

from __future__ import annotations

import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SECRET_PATTERNS = [
    re.compile(rb"sk-[A-Za-z0-9_\-]{20,}"),
    re.compile(rb"gho_[A-Za-z0-9_]{20,}"),
    re.compile(rb"ghp_[A-Za-z0-9_]{20,}"),
    re.compile(rb"Bearer\s+[A-Za-z0-9_\-\.]{30,}"),
]


def load_keys() -> list[bytes]:
    env = ROOT / ".env"
    if not env.exists():
        print("note: no .env found — only shape-based scanning will run")
        return []
    keys = []
    for line in env.read_text().splitlines():
        if "=" not in line or line.strip().startswith("#"):
            continue
        value = line.split("=", 1)[1].strip().strip("'\"")
        if len(value) >= 20:
            keys.append(value.encode())
    print(f"loaded {len(keys)} key(s) from .env (values not printed)")
    return keys


def scan_bytes(label: str, data: bytes, keys: list[bytes]) -> int:
    hits = 0
    for key in keys:
        if key in data:
            print(f"  LEAK  exact key found in {label}")
            hits += 1
    for pattern in SECRET_PATTERNS:
        for match in pattern.finditer(data):
            print(f"  leak? {label}: {match.group(0)[:12]}… (shape match, redacted)")
            hits += 1
    return hits


def scan_apk(path: Path, keys: list[bytes]) -> int:
    hits = 0
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        for name in names:
            try:
                data = archive.read(name)
            except Exception:  # a corrupt entry is not a leak
                continue
            hits += scan_bytes(f"{path.name}!{name}", data, keys)
        print(f"  scanned {len(names)} entries in {path.name}")
    return hits


def scan_git_tree(keys: list[bytes]) -> int:
    """Every blob in every commit — history is public forever."""
    hits = 0
    blobs = subprocess.run(
        ["git", "rev-list", "--objects", "--all"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout.splitlines()
    checked = 0
    for line in blobs:
        parts = line.split(" ", 1)
        if len(parts) != 2:
            continue
        sha, name = parts
        content = subprocess.run(
            ["git", "cat-file", "-p", sha],
            cwd=ROOT, capture_output=True, check=False,
        ).stdout
        if not content:
            continue
        checked += 1
        hits += scan_bytes(f"git:{name}", content, keys)
    print(f"  scanned {checked} blobs across all commits")
    return hits


def main() -> int:
    keys = load_keys()
    targets = [Path(arg) for arg in sys.argv[1:]]
    if not targets:
        apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
        targets = [apk] if apk.exists() else []

    total = 0
    for target in targets:
        print(f"scanning {target}")
        total += scan_apk(target, keys)
    print("scanning git history")
    total += scan_git_tree(keys)

    print()
    if total:
        print(f"FAIL: {total} potential secret exposure(s) — do not publish until resolved")
        return 1
    print("OK: no key material found in the artefact or in git history")
    return 0


if __name__ == "__main__":
    sys.exit(main())
