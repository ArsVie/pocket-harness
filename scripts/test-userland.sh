#!/usr/bin/env bash
# Host-side checks for the shipped userland, runnable without a device.
#
#   scripts/test-userland.sh
#
# Covers what can be covered off-device: the archive hashes to the recorded digest, the shim moves
# files into the trash instead of unlinking them, and the shim reports missing paths the way `rm`
# does. On-device behaviour (exec from app storage, PATH ordering, the real workspace trash dir) is
# proven by the exec probe in planning/PLAN.md W0.3, not here.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHIM="$ROOT/userland/rm.sh"
FAILURES=0

note() { printf '  %s\n' "$*"; }
ok() { printf 'ok   %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

echo "sha256"
EXPECTED="$(awk '{print $1}' "$ROOT/userland/SHA256SUMS")"
for f in "$ROOT/userland/busybox" "$ROOT/app/src/main/assets/userland/busybox"; do
    ACTUAL="$(sha256sum "$f" | awk '{print $1}')"
    if [ "$ACTUAL" = "$EXPECTED" ]; then ok "$(basename "$(dirname "$f")")/$(basename "$f") $ACTUAL"
    else fail "$f is $ACTUAL, expected $EXPECTED"; fi
done

echo "rm shim"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/work/sub"
printf 'keep\n' > "$WORK/work/keep.txt"
printf 'gone\n' > "$WORK/work/sub/gone.txt"

pushd "$WORK/work" >/dev/null
if PH_TRASH_DIR="$WORK/work/.trash" sh "$SHIM" -rf sub; then ok "rm -rf sub exits 0"; else fail "rm -rf sub exited $?"; fi
popd >/dev/null

[ -d "$WORK/work/sub" ] && fail "sub still exists (was not moved)" || ok "sub no longer in place"
MOVED="$(find "$WORK/work/.trash" -maxdepth 1 -name '*-sub' | head -1)"
[ -n "$MOVED" ] && ok "moved to $MOVED" || fail "nothing matching *-sub in the trash dir"
[ -f "$MOVED/gone.txt" ] && ok "contents travelled with the directory" || fail "gone.txt did not travel"
[ -f "$WORK/work/keep.txt" ] && ok "sibling file untouched" || fail "keep.txt was damaged"

set +e
OUT="$(cd "$WORK/work" && PH_TRASH_DIR="$WORK/work/.trash" sh "$SHIM" nosuch 2>&1)"
RC=$?
set -e
[ "$RC" -eq 1 ] && ok "missing path exits 1" || fail "missing path exited $RC"
case "$OUT" in *"No such file or directory"*) ok "missing path message: $OUT" ;;
                 *) fail "unexpected message: $OUT" ;; esac

echo "busybox applets (host-side sanity, arm64 binary is not runnable on x86_64 hosts)"
python3 - "$ROOT/userland/busybox" <<'PY'
import struct, sys
machine = struct.unpack('<H', open(sys.argv[1], 'rb').read(20)[18:20])[0]
print(f"ok   ELF machine {hex(machine)} ({'aarch64' if machine == 0xB7 else 'NOT aarch64'})")
sys.exit(0 if machine == 0xB7 else 1)
PY

echo
if [ "$FAILURES" -eq 0 ]; then echo "userland: all checks passed"; else echo "userland: $FAILURES check(s) failed"; exit 1; fi
