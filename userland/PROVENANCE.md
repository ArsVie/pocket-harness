# userland/ — provenance and packaging

What ships as the device-side shell for the harness, where it came from, and how it is deployed.
Verified by the orchestrator; see `../planning/ENVIRONMENT.md` ("v2: the bash userland") and
`../planning/decisions/0006-shell-userland-bash.md`.

## Contents

| File | What |
|---|---|
| `SHA256SUMS` | `sha256sum` digests of the shipped shell binaries (paths relative to the repo root; checked by `scripts/test-userland.sh`) |
| `rm.sh` | The `rm` → trash shim — verbatim copy of the file that ships (`../app/src/main/assets/userland/rm.sh`) |
| `../app/src/main/assets/userland/bash-x86_64` | GNU bash 5.3, x86_64, dynamically linked (1,284,544 bytes) |
| `../app/src/main/assets/userland/bash-aarch64` | GNU bash 5.3, arm64-v8a, dynamically linked (1,288,736 bytes) |

The binaries reach the device by being unpacked from assets to `files/userland/bash`
(`Userland.provision`); the per-ABI pick uses `Build.SUPPORTED_ABIS` order.

## Source and build

```
https://ftp.gnu.org/gnu/bash/bash-5.3.tar.gz
```

Built by `scripts/build-bash-android.sh` with NDK 27.2.12479018 (clang, API 28, bionic) and
`strip`ped. Static linking fails — bionic has no static libc — so the shipped binaries are dynamic
against system libraries only, which is verified by `readelf -d`: `NEEDED` = `libc.so`, `libdl.so`;
interpreter `/system/bin/linker64`.

The script carries the one non-obvious configure knob, `bash_cv_getcwd_malloc=yes`. Its
justification is the on-device probe `scripts/getcwd-probe.c`; the postmortem (cross-compiling
without it compiles in bash's walk-up `getcwd` emulation, which prints a permission error on every
run in app storage) is in ENVIRONMENT.md.

## Licence

**GPL-3.0-or-later** (bash). This is the one non-MIT artefact in the project. Bundling it in a
distributed APK imports the standard GPL obligations (source availability, licence text, no added
restrictions); the source is the GNU tarball above and the recipe is the build script. Private,
sideloaded PoC use is unaffected. The harness code under `core/` and `app/` stays MIT.

## Deployment on the device

`Userland.provision` (app) unpacks the bash asset for this device's ABI to `files/userland/bash`,
`chmod +x`, and `AndroidShellBinaries.resolve()` self-tests it with a command-carrying invocation
(`bash -c "echo selftest"`). If it passes, the shell is bash; if not, the platform shell
(`/system/bin/sh`) is used. `PATH` is the `rm` shim dir first, then `/system/bin`. The shim file
carries a fixed `#!/system/bin/sh` shebang, so it always runs under the platform's mksh no matter
which shell the harness execs (ADR-004 §5, ADR-006).

## ABI note

The runtime picks the asset by `Build.SUPPORTED_ABIS` order: the API-36 `x86_64` emulator takes the
x86_64 build; an arm64 phone takes `arm64-v8a`. Both are first-class — no NDK translation involved.
