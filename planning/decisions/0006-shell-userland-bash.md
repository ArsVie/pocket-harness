# ADR-PH-006 — The shipped userland: GNU bash 5.3, built for Android

| Status | Accepted |
|---|---|
| Date | September 2026 |
| Author | Ars (decision), Hermes (record) |
| Amends | ADR-001 (userland conclusion); the v1 spec's "no NDK" note |

## Context

ADR-001 asked "which userland ships", and the September 2026 answer was the *platform's* shell:
Android's mksh + toybox, because the app process carries a seccomp filter that a static (musl)
busybox cannot survive (ENVIRONMENT.md, measured). That answer is correct, but it leaves the harness
promising less than a `bash` tool implies: mksh is not bash — no arrays, no `[[ ]]`, no `pipefail` —
and the tool description had to teach around it.

The owner asked for the real thing in v2: bash on the device, honestly — or keep the platform shell
and say so in the description.

## Decision

1. **Ship GNU bash 5.3 as the preferred userland.** Built with the NDK (clang, API 28, bionic),
   dynamically linked against system libraries only (`libc.so`, `libdl.so`, interpreter
   `/system/bin/linker64`). One build per ABI (`x86_64`, `arm64-v8a`); the runtime picks by
   `Build.SUPPORTED_ABIS` order.
2. **The platform shell is the fallback, chosen at runtime, not build time.**
   `AndroidShellBinaries.resolve()` self-tests the bundled bash with a command-carrying invocation
   and falls back to `/system/bin/sh` if it fails — a device that someday blocks the bundled shell
   still gets a working harness.
3. **The proof is in-app, not on the bench.** The bundled bash was exec'd from app data *in the app
   process*, under the inherited seccomp filter, with `Seccomp: 2` in the child — the exact
   condition that kills static busybox. Raw rows: `files/exec-probe.txt` (Experiment C).
4. **The `getcwd` stderr noise is fixed at configure, not with a band-aid.**
   `bash_cv_getcwd_malloc=yes` (justified on-device by `scripts/getcwd-probe.c`) selects bionic's
   allocating `getcwd(NULL, 0)` over bash's cross-compiled walk-up emulation. Rebuilt; the noise is
   gone.
5. **Busybox is retired.** The asset, its `--install` machinery and the resolve candidate are
   removed; provisioning deletes leftovers from upgraded installs. The busybox dead end stays
   documented (ENVIRONMENT.md) — it is the reason the seccomp constraint was understood at all.
6. **Amends the v1 spec's "no NDK" note:** the NDK now builds the *shipped userland*, and nothing
   else. No JNI, no native harness code.
7. **Licence posture.** Bash is GPL-3.0-or-later; distributing the APK imports the standard GPL
   obligations. The record is `scripts/build-bash-android.sh` (recipe) and `userland/PROVENANCE.md`
   + `userland/SHA256SUMS` (hashes, upstream source pointer). Kept honest for a sideloaded PoC.

## Consequences

**Upside.** The `bash` tool description can say "GNU bash 5.3" and mean it: arrays, `[[ ]]`,
`pipefail`, and the reference presets' POSIX-vs-GNU footgun language mostly disappears. The fallback
story is better than before: platform mksh if the bundled binary cannot start.

**Costs.** +2.6 MB of assets; a per-ABI binary pair to keep in sync (hashes enforced by
`scripts/test-userland.sh`); one more GPL component in a mostly-MIT repo. Static linking was tried
first and is impossible against bionic — dynamic is the only shape that works, which is why the
binaries are not portable single files.

## References

- ENVIRONMENT.md "v2: the bash userland" — build facts, seccomp rows, the getcwd postmortem.
- ADR-001 (execution surface — the open "which userland" gap this closes), ADR-004 §5 (rm shim under
  whichever shell wins), ADR-005 §8 (targetSdk 28 keeps app-data exec legal — bash relies on that
  legality exactly as busybox did).
- `scripts/build-bash-android.sh`, `scripts/getcwd-probe.c`, `userland/PROVENANCE.md`.
