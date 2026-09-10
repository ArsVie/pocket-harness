#!/bin/sh
# PocketHarness `rm` shim — moving to trash instead of unlinking (ADR-004 §5).
#
# Deployed by the app with @SHELL@ substituted for the absolute path of the shipped busybox
# shell, and placed FIRST on PATH ahead of the busybox applets. `PH_TRASH_DIR` is exported by the
# tool layer as `<workspace>/.trash`; if it is unset the shim falls back to `$PWD/.trash`.
#
# Known limits, accepted in ADR-002 and ADR-004: this intercepts `rm` invoked by name. A command
# that reaches the applet another way (`busybox rm -rf x`) or a binary that calls unlink(2) is not
# intercepted. The floor denies the catastrophic shapes before exec; this shim catches the rest.
#
# Flags are accepted and ignored: -r/-f/-rf/-i describe how a recursive delete would proceed, and
# moving a directory wholesale needs none of them. Globs are expanded by the invoking shell, so
# paths arrive already resolved.

TRASH="${PH_TRASH_DIR:-$PWD/.trash}"
mkdir -p "$TRASH" 2>/dev/null || {
    echo "rm: cannot create trash directory $TRASH" >&2
    exit 1
}

STAMP=$(date +%s)
RC=0

for TARGET in "$@"; do
    case "$TARGET" in
        -*) continue ;;                       # rm flags carry no path
        --) continue ;;
    esac

    if [ ! -e "$TARGET" ] && [ ! -L "$TARGET" ]; then
        echo "rm: $TARGET: No such file or directory" >&2
        RC=1
        continue
    fi

    NAME=$(basename "$TARGET")
    if ! mv -f "$TARGET" "$TRASH/$STAMP-$NAME" 2>/dev/null; then
        echo "rm: cannot remove '$TARGET'" >&2
        RC=1
    fi
done

exit $RC
