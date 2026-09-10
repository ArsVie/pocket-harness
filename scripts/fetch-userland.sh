#!/usr/bin/env bash
# Fetch and verify the device-side userland. Re-running this must reproduce byte-identical files;
# if the sha256 below changes, the provenance record in userland/PROVENANCE.md is stale and a human
# has to look at it before the diff is accepted.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
URL="https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/busybox-static-1.37.0-r14.apk"
EXPECTED_SHA256="$(awk '{print $1}' "$ROOT/userland/SHA256SUMS")"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "fetching $URL"
curl -fsSL -o "$TMP/busybox.apk" "$URL"
tar -xzf "$TMP/busybox.apk" -C "$TMP"
[ -f "$TMP/bin/busybox.static" ] || { echo "no bin/busybox.static in the package" >&2; exit 1; }

ACTUAL="$(sha256sum "$TMP/bin/busybox.static" | awk '{print $1}')"
if [ "$ACTUAL" != "$EXPECTED_SHA256" ]; then
    echo "sha256 mismatch" >&2
    echo "  expected $EXPECTED_SHA256" >&2
    echo "  actual   $ACTUAL" >&2
    echo "Update userland/SHA256SUMS and PROVENANCE.md deliberately, not by regenerating blindly." >&2
    exit 1
fi

install -m 0755 "$TMP/bin/busybox.static" "$ROOT/userland/busybox"
install -m 0644 "$TMP/.PKGINFO" "$ROOT/userland/.PKGINFO"
cp "$TMP"/.SIGN.RSA.* "$ROOT/userland/"
install -m 0644 "$TMP/bin/busybox.static" "$ROOT/app/src/main/assets/userland/busybox"
echo "ok: userland/busybox and app/src/main/assets/userland/busybox match $EXPECTED_SHA256"
