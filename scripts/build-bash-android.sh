#!/usr/bin/env bash
# pocket-harness v2 shell experiment: GNU bash for Android (bionic), built with the NDK.
# Static first (single file, zero runtime deps); dynamic fallback (system libs only).
# Outputs: out/bash-x86_64, out/bash-aarch64, EVIDENCE.txt, logs/, status.txt
set -uo pipefail

ROOT=/home/vruizes/build/pocket-bash
OUT="$ROOT/out"; LOGS="$ROOT/logs"
mkdir -p "$OUT" "$LOGS"
say() { echo "$*" | tee -a "$OUT/status.txt"; }
say "== pocket-bash build start $(date -Is)"

SDKMANAGER="$HOME/android-sdk/cmdline-tools/latest/bin/sdkmanager"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

# ---- 1. NDK ------------------------------------------------------------------
NDK_DIR=$(ls -d "$HOME"/android-sdk/ndk/* 2>/dev/null | sort -V | tail -1)
if [ -z "${NDK_DIR:-}" ]; then
  say "[1/4] installing NDK (~1.5 GB download)..."
  for v in 27.2.12479018 26.3.11579264 28.0.13004108; do
    { yes | "$SDKMANAGER" --install "ndk;$v"; } > "$LOGS/ndk-install-$v.log" 2>&1 || true
    NDK_DIR=$(ls -d "$HOME"/android-sdk/ndk/* 2>/dev/null | sort -V | tail -1)
    [ -n "${NDK_DIR:-}" ] && break
  done
fi
if [ -z "${NDK_DIR:-}" ]; then
  latest=$(yes | "$SDKMANAGER" --list 2>/dev/null | grep -o 'ndk;[0-9][0-9.]*' | sort -V | tail -1)
  say "[1/4] retry with $latest"
  { yes | "$SDKMANAGER" --install "$latest"; } > "$LOGS/ndk-install-latest.log" 2>&1 || true
  NDK_DIR=$(ls -d "$HOME"/android-sdk/ndk/* 2>/dev/null | sort -V | tail -1)
fi
[ -n "${NDK_DIR:-}" ] || { say "FATAL: NDK unavailable; see $LOGS/ndk-install*.log"; exit 1; }
say "[1/4] NDK: $NDK_DIR"
TC="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
[ -x "$TC/bin/clang" ] || { say "FATAL: toolchain clang missing under $TC"; exit 1; }

# ---- 2. bash source ----------------------------------------------------------
BASH_VER=""
for v in 5.3 5.2.37; do
  if [ -f "$ROOT/bash-$v.tar.gz" ] || \
     curl -fsSL --retry 3 -o "$ROOT/bash-$v.tar.gz" "https://ftp.gnu.org/gnu/bash/bash-$v.tar.gz" || \
     curl -fsSL --retry 3 -o "$ROOT/bash-$v.tar.gz" "https://mirrors.kernel.org/gnu/bash/bash-$v.tar.gz"; then
    BASH_VER=$v; break
  fi
done
[ -n "$BASH_VER" ] || { say "FATAL: cannot fetch bash source"; exit 1; }
[ -d "$ROOT/bash-$BASH_VER" ] || tar -xzf "$ROOT/bash-$BASH_VER.tar.gz" -C "$ROOT"
say "[2/4] bash source: $BASH_VER"
"$ROOT/bash-$BASH_VER/configure" --help 2>/dev/null | grep -q -- '--disable-readline' && ROPT="--disable-readline" || ROPT=""

# ---- 3. build per ABI --------------------------------------------------------
# $1 abi, $2 clang host triple, $3 output name, $4 LDFLAGS, $5 tag, $6 extra configure flag
build_one() {
  local abi="$1" host="$2" ourname="$3" ldflags="$4" tag="$5" extra="$6"
  local bdir="$ROOT/build-$abi-$tag"; rm -rf "$bdir"; mkdir -p "$bdir"
  say "[3/4] $abi/$tag configure"
  ( cd "$bdir" && \
    CC="$TC/bin/${host}28-clang" CFLAGS="-O2 -fPIE" \
    LDFLAGS="$ldflags -pie -Wl,-z,max-page-size=16384" \
    bash_cv_getcwd_malloc=yes \
    "$ROOT/bash-$BASH_VER/configure" --host="$host" \
      --without-bash-malloc --disable-nls $ROPT $extra \
      > "$LOGS/configure-$abi-$tag.log" 2>&1 ) || return 1
  say "[3/4] $abi/$tag make"
  ( cd "$bdir" && make -j"$(nproc)" > "$LOGS/make-$abi-$tag.log" 2>&1 ) || return 1
  cp -f "$bdir/bash" "$OUT/$ourname"
  "$TC/bin/llvm-strip" --strip-unneeded "$OUT/$ourname" 2>/dev/null || true
  return 0
}

for spec in "x86_64 x86_64-linux-android bash-x86_64" "aarch64 aarch64-linux-android bash-aarch64"; do
  set -- $spec; abi=$1; host=$2; name=$3
  if [ "${POCKET_BASH_SKIP_STATIC:-0}" = "1" ]; then
    if build_one "$abi" "$host" "$name" "" "dynamic" ""; then
      say "[3/4] $abi: DYNAMIC ok (static skipped)"
    else
      say "[3/4] $abi: FAILED (dynamic, static skipped; see logs)"
    fi
  elif build_one "$abi" "$host" "$name" "-static" "static" "--enable-static-link"; then
    say "[3/4] $abi: STATIC ok"
  elif build_one "$abi" "$host" "$name" "" "dynamic" ""; then
    say "[3/4] $abi: DYNAMIC ok (static failed; see logs)"
  else
    say "[3/4] $abi: FAILED (static and dynamic; see logs)"
  fi
done

# ---- 4. evidence -------------------------------------------------------------
{
  echo "# pocket-bash evidence — $(date -Is)"
  echo "NDK: $NDK_DIR"
  echo "bash source: $BASH_VER"
  for f in "$OUT"/bash-*; do
    [ -f "$f" ] || continue
    echo; echo "## $(basename "$f")"
    ls -l "$f"
    file "$f" 2>/dev/null || true
    "$TC/bin/llvm-readelf" -h "$f" | grep -E 'Class|Type:|Machine|Entry'
    echo "-- interpreter:"; "$TC/bin/llvm-readelf" -l "$f" | grep -i interpreter || echo "   (none — static)"
    echo "-- NEEDED:"; "$TC/bin/llvm-readelf" -d "$f" 2>/dev/null | grep NEEDED || echo "   (none)"
    sha256sum "$f"
  done
} > "$OUT/EVIDENCE.txt" 2>&1

say "== DONE $(date -Is)"
for f in "$OUT"/bash-*; do [ -f "$f" ] && say "-> $f ($(stat -c%s "$f") bytes)"; done
