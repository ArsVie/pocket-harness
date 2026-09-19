#!/usr/bin/env bash
# Host-side checks for the shipped userland (ADR-006), runnable without a device.
#
#   scripts/test-userland.sh
#
# What can be checked off-device: the shipped shell binaries hash to the recorded digests, each is
# an Android-built PIE for the expected architecture, the shim carries its fixed shebang, and the
# rm shim — the exact file the app deploys — moves files into the trash instead of unlinking them
# and reports missing paths the way rm does. On-device behaviour (in-app exec under the app's
# seccomp filter, PATH ordering, the real workspace trash dir) is proven by the in-app exec probe,
# not here — see planning/ENVIRONMENT.md "v2: the bash userland".
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHIM="$ROOT/app/src/main/assets/userland/rm.sh"
FAILURES=0

ok() { printf 'ok   %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

echo "sha256 (paths relative to the repo root)"
if HASHES="$(cd "$ROOT" && sha256sum -c userland/SHA256SUMS 2>&1)"; then
    printf '%s\n' "$HASHES" | while IFS= read -r line; do ok "$line"; done
else
    fail "$HASHES"
fi

echo "ELF shape"
for pair in "bash-aarch64:0xb7" "bash-x86_64:0x3e"; do
    NAME="${pair%%:*}"; WANT="${pair##*:}"
    if GOT="$(python3 - "$ROOT/app/src/main/assets/userland/$NAME" <<'PY'
import struct, sys
d = open(sys.argv[1], 'rb').read(20)
assert d[:4] == b'\x7fELF', 'not an ELF'
print(hex(struct.unpack('<H', d[18:20])[0]))
PY
)"; then
        if [ "$GOT" = "$WANT" ]; then ok "$NAME: ELF machine $GOT"
        else fail "$NAME: ELF machine $GOT, expected $WANT"; fi
    else
        fail "$NAME: not an ELF"
    fi
done

echo "rm shim (the file the app deploys)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/work/sub"
printf 'keep\n' > "$WORK/work/keep.txt"
printf 'gone\n' > "$WORK/work/sub/gone.txt"

pushd "$WORK/work" >/dev/null
if PH_TRASH_DIR="$WORK/work/.trash" sh "$SHIM" -rf sub; then ok "rm -rf sub exits 0"; else fail "rm -rf sub exited $?"; fi
popd >/dev/null

if [ -d "$WORK/work/sub" ]; then fail "sub still exists (was not moved)"; else ok "sub no longer in place"; fi
MOVED="$(find "$WORK/work/.trash" -maxdepth 1 -name '*-sub' 2>/dev/null | head -1)"
if [ -n "$MOVED" ]; then ok "moved to $MOVED"; else fail "nothing matching *-sub in the trash dir"; fi
if [ -f "$MOVED/gone.txt" ]; then ok "contents travelled with the directory"; else fail "gone.txt did not travel"; fi
if [ -f "$WORK/work/keep.txt" ]; then ok "sibling file untouched"; else fail "keep.txt was damaged"; fi

set +e
OUT="$(cd "$WORK/work" && PH_TRASH_DIR="$WORK/work/.trash" sh "$SHIM" nosuch 2>&1)"
RC=$?
set -e
if [ "$RC" -eq 1 ]; then ok "missing path exits 1"; else fail "missing path exited $RC"; fi
case "$OUT" in *"No such file or directory"*) ok "missing path message: $OUT" ;;
                 *) fail "unexpected message: $OUT" ;; esac

if [ "$(head -1 "$SHIM")" = "#!/system/bin/sh" ]; then ok "shim shebang is #!/system/bin/sh"
else fail "shim shebang: $(head -1 "$SHIM")"; fi

echo
if [ "$FAILURES" -eq 0 ]; then echo "userland: all checks passed"; else echo "userland: $FAILURES check(s) failed"; exit 1; fi
