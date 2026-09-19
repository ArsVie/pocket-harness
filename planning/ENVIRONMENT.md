# Environment — verified on this machine, September 2026

Everything below was read or executed by the agent; nothing here is assumed. Commands that produced
a fact are noted, so any of it can be re-checked.

## Devices

| Fact | Value | How it was checked |
|---|---|---|
| AVD present | `harness.avd` | `ls ~/.android/avd` (Windows side) |
| Running device | `emulator-5554` — `sdk_gphone64_x86_64`, API 36 | `adb devices -l`, `getprop ro.build.version.sdk` |
| Emulator kernel | `6.6.66-android15-8` | `adb shell uname -a` |
| Emulator ABIs | `x86_64,arm64-v8a`, `ro.enable.native.bridge.exec=1`, `libndk_translation.so` present | `getprop ro.product.cpu.abilist`, `getprop ro.enable.native.bridge.exec`, `ls /system/lib64` |
| **arm64 execution on the x86_64 emulator** | **works, but only outside the app process** — `busybox` aarch64 static printed `HELLO_ARM64`, ran its `sh` (ash), and ran applets from symlinks | pushed `/data/local/tmp/busybox`, ran it as the **shell user** (see the correction below) |
| Phone (wireless adb) | address is local configuration, not committed — the LAN address, or a private overlay-network address passed as `PHONE_ADB_ALT` | `/mnt/c/dev/experiments/phone/README.md`, `phone.py`; **unreachable at the time of writing** |
| Windows adb | `C:\dev\android-sdk\platform-tools\adb.exe` (also a WinGet copy referenced by `core.py`) | `ls`; `adb devices` |
| WSL → Windows adb server | works: `ADB_SERVER_SOCKET=tcp:127.0.0.1:5037` then `adb devices` lists `emulator-5554` | python socket probe + `adb devices` |

The last row is what lets Gradle on the WSL side install and instrument against the Windows-hosted
emulator. Without it the build and the device would live in different worlds.

## SDKs

| Fact | Value |
|---|---|
| Windows SDK | `C:\dev\android-sdk` — `platforms/android-36`, `build-tools/36.0.0`, `platform-tools`, `emulator`, `system-images`. **No NDK.** |
| Linux SDK (installed by the agent) | `~/android-sdk` — `platforms/android-36`, `build-tools/36.0.0`, `platform-tools` (= `~/android-sdk/platform-tools/adb`), `cmdline-tools/latest`. Licenses accepted. |
| JDK (WSL) | OpenJDK 21.0.12 |
| JDK (Windows) | JDK 17 only, inside `~/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2` |
| Gradle | Wrapper pins **Gradle 9.7.1** (downloaded by the wrapper; the Windows `gradle-9.1.0-bin` cache is a different project's). Kotlin 2.4.20, AGP 9.4.0, Compose BOM 2026.06.01, JUnit 6.1.3 — all resolved and building. |
| Network from WSL | `dl.google.com` and `services.gradle.org` reachable |

Builds run on the Linux side (`/home/vruizes/projects/pocket-harness` is on the ext4 fs). The Windows
SDK is not used by the build; it is used for the emulator/AVD and as an adb source. Build-tools and
platform versions are deliberately identical (36 / 36.0.0) on both sides.

## W^X: the exec path, resolved empirically

ADR-001 left open *which* of two mechanisms would actually permit execution on Android 10+. It was
settled by running it, not by argument:

| Step | Result |
|---|---|
| Push the static aarch64 busybox to `/data/local/tmp` and exec it as the shell user | works (control; also the emulator's arm64 translation check) |
| Copy it into the app's own data dir (`/data/user/0/com.arsvie.pocketharness/files/userland/`), `chmod 700`, exec it as the **app's uid** (`u0_a217`), SELinux context `app_data_file`, on the API-36 emulator, with the app installed at `targetSdk 28` | **works** — `APP_DATA_EXEC_OK`, and `./busybox sh -c "echo ABS_OK; pwd"` printed `ABS_OK` and the real working directory |

So the primary path in ADR-001 (unpack from assets into app storage, exec in place) is live at
`targetSdk 28` on API 36, and the `jniLibs` fallback is not needed. Two honest caveats:

- The check above ran through `run-as`, which uses the `runas_app` SELinux domain — a sibling of, not
  identical to, the app's own domain. The definitive in-process proof is the app's own exec probe
  (W1.E2), which must run exec from the app process itself. Treat the table above as strong evidence,
  not as the proof.
- `/data` is mounted `nosuid,nodev` (verified). That blocks setuid/setgid tricks and device nodes; it
  does not block a static executable, which is what ships.

The APK currently has no `jniLibs`, so `useLegacyPackaging = true` in `app/build.gradle.kts` is a
no-op — the same file's comment says so, deliberately, so nobody later reads it as the reason exec
works.

## App-process execution: the real constraint is seccomp, not W^X

The section above establishes that exec **of** app data works. That is necessary but not sufficient,
and the gap cost a full verification round. Measured in-app, on the API-36 emulator, with the app
installed at `targetSdk 28`:

| Row | Result |
|---|---|
| `busybox --help` executed from the app process | **exit 0**, full multi-KB stdout |
| `busybox echo hi` (applet dispatch) | exit 159 = 128 + **31 (SIGSYS)**, empty stdout/stderr |
| `busybox sh -c 'echo hi'` | exit 159 (SIGSYS) |
| `busybox uname -a`, `busybox true`, `bin/true` symlink | exit 159 (SIGSYS) |
| The same binary, same app-data path, run as the shell user (`run-as`) | **works** |
| An x86_64 static busybox in the app process | **`--help` also traps** — so this is not an ARM-translation artifact |
| `grep Seccomp /proc/<app pid>/status` | `Seccomp: 2`, `Seccomp_filters: 1` |
| `grep Seccomp /proc/self/status` as the shell user | `Seccomp: 0` |

**Conclusion.** Android installs a seccomp filter on the app process. Filters are inherited across
`execve`, so every child the app spawns inherits it and dies on any syscall the filter does not allow.
`--help` survives because it never reaches the applet-dispatch code path; a static musl binary like
busybox does reach it, immediately. This is a property of the *app process domain*, not of the file
mode, the mount, the target SDK, or the device architecture — so it would equally apply on a real
arm64 phone.

**Correction to the record above.** The earlier "arm64 execution works" row was measured by pushing
the binary to `/data/local/tmp` and running it as the **shell** user, whose process carries no filter.
It proved ARM translation works; it did not prove the app can use a busybox userland, and it was
written up too generously. The in-process probe is what settled it.

**Consequences, resolved.** Both candidates were tested in the app process on the API-36 emulator;
the raw rows are in `files/exec-probe.txt` on the device and reproduced by
`adb -s emulator-5554 shell run-as com.arsvie.pocketharness cat files/exec-probe.txt`.

| Candidate | Result |
|---|---|
| busybox as `jniLibs/arm64-v8a/libbusybox.so`, executed from `/data/app/.../lib/arm64/` | **Dead.** Two independent reasons, both recorded: (a) busybox dispatches its applet from `argv[0]`'s basename, so `libbusybox.so --help` is `exit 127, applet not found` — a naming failure, not a policy one; (b) exec'd through a symlink renamed `busybox`, `--help` returns 0 but `echo` and `sh -c` return **159 (SIGSYS)**, exactly as from app data. The exec *directory* is not the variable: the filter is inherited across `execve` wherever the bytes live. |
| The platform shell — `/system/bin/sh` (mksh) + toybox | **Works.** `sh -c 'echo OK; grep Seccomp /proc/self/status; id -u'` runs from the app process, and the child reports `Seccomp: 2` — it inherits the filter and still works, because bionic tools are built against it. `toybox echo`, an applet called by name (`/system/bin/echo`), and `/system/bin/grep` all return 0 with real output. A *copy* of `/system/bin/sh` also execs from app data, and a `#!/system/bin/sh` script from app data runs — which is the exact shape of the `rm` shim. |

So: **the harness's userland is Android's own bionic shell and toybox**, selected at runtime by a
self-test (`AndroidShellBinaries.resolve()`), with the asset busybox retained as a fallback that will
simply never win on Android. This resolves ADR-001's open gap "which userland ships": not busybox, not
a Termux bootstrap, but the platform's. The `bash` tool interface is unchanged — it is still a real
shell over a real filesystem, which is the property ADR-001 actually needed.

**v2 update:** the userland is now the *bundled* GNU bash, with this platform shell as the runtime
fallback — see "v2: the bash userland" below and ADR-006.

Proven end to end in-app (orchestrator-verified, not taken on report): `rm work/doomed.txt` left
`work/` containing only `keep.txt`, and the file reappeared as
`files/workspace/.trash/<stamp>-doomed.txt`, with the deployed shim's first line being
`#!/system/bin/sh`.

Two honest costs of this decision:

1. It is no longer literally "Termux-style". The reference presets' tool *descriptions* assume a
   Linux userland with GNU tools; toybox and mksh are smaller than busybox's applet set in places,
   and the `bash` description must not promise what the platform shell lacks. The description is
   orchestrator-owned and says "busybox ash" nowhere, but it should be re-read against the mksh/toybox
   reality.
2. The `rm` shim's applet usage is now constrained by what mksh + toybox provide (`mkdir`, `date`,
   `basename`, `mv` are toybox; `case`/`[` are mksh builtins). It works today; anything added to it
   later has to be checked against that set.


## Lint and `targetSdk 28`

`lintDebug` fails on exactly one check at `targetSdk 28`: `ExpiredTargetSdkVersion`, which encodes a
Google **Play** distribution policy. Play is out of scope (ADR-001), so that single check is disabled
in `app/build.gradle.kts` with a comment naming the ADR; every other lint check stays on and remains
fatal. The alternative (raise `targetSdk`, ship the userland as a native library) is recorded as the
fallback and is deliberately *not* taken — the decision was to keep the mechanism the ADR chose.


## Userland (the shell the harness ships)

| Fact | Value |
|---|---|
| Candidate | Alpine `busybox-static` 1.37.0-r14, `aarch64` |
| Source | `https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/busybox-static-1.37.0-r14.apk` (a `.tar.gz`; member `bin/busybox.static`) |
| Size / type | 1,115,944 bytes, ELF 64-bit LSB executable, machine `0xb7` (aarch64), static |
| Provenance check | Alpine package signature member present (`.SIGN.RSA.alpine-devel@…`); Alpine 3.21 arm64 `main`; the older 3.20 filename from a first guess 404'd and was discarded rather than substituted |
| Verified to run | on the API-36 x86_64 emulator via NDK translation (see Devices) |
| Why static | no libc coupling, no `/system` dependency, runs from `/data/app/.../lib/arm64/` and under translation |
| Licence | **GPL-2.0** (busybox). The repo default is MIT; bundling busybox in a distributed APK imports GPL obligations. Private sideloaded PoC only — recorded so it is not discovered later. |

## Constraints carried in from Part 1

- Android 10 (API 29) removed execute permission for the app home directory (W^X). Exec from the
  APK's native library dir (`/data/app/.../lib/<abi>/`, `extractNativeLibs=true`) remains permitted;
  pinning `targetSdk ≤ 28` is the other known path. ADR-001; the PoC takes targetSdk 28 (ADR-005).
- Anything that must run under translation should avoid `madvise`/`memfd`-style tricks busybox does
  not need in the first place — the observed run is the evidence that the simple path works.

## v2: the bash userland (September 2026)

The platform shell works, but it is mksh + toybox — not the GNU bash a `bash` tool implies (no
`[[ ]]`, no arrays, no `pipefail`). v2 closes that gap: the app now ships **GNU bash 5.3**, built
for Android with the NDK, and prefers it; `/system/bin/sh` stays as the runtime fallback (ADR-006).

| Step | Result |
|---|---|
| Build | `scripts/build-bash-android.sh` — bash 5.3 from the GNU sources; static link fails (bionic has no static libc), dynamic link succeeds and `readelf -d` shows only system libs (`libc.so`, `libdl.so`, interp `/system/bin/linker64`) |
| Artifacts | `bash-x86_64` 1,284,544 bytes; `bash-aarch64` 1,288,736 bytes; digests in `userland/SHA256SUMS` |
| **In-app run (the real test)** | exec'd from app data as the app uid, `Seccomp: 2` inherited: `exit 0`; `BASH_VERSION=5.3.0(1)-release`; arrays, `[[ ]]`, `pipefail`, pipelines, child exec through PATH (toybox), and the `rm` shim via kernel shebang — all green, **empty stderr** |
| Same process, static musl busybox | still SIGSYS (exit 159) at applet dispatch — the filter is the discriminator, not the exec location, and bionic-built code passes it |

One real bug was found and fixed at the root rather than papered over: bash cross-compiled (no
configure runtime test) so `GETCWD_BROKEN` got compiled in, and every invocation printed
`getcwd: cannot access parent directories: Permission denied` while walking up from the workspace
(app storage under `/data`, which the app cannot list — `drwxrwx--x`). The fix is one configure
cache variable, `bash_cv_getcwd_malloc=yes`: `scripts/getcwd-probe.c` proved on-device that
bionic's `getcwd(NULL, 0)` allocates correctly from exactly that directory, so bash is configured
to use the system call instead of its own walk-up emulation. Rebuilt clean; the noise is gone, not
filtered.

Runtime shape: `assets/userland/bash-<abi>` unpacks to `files/userland/bash` per
`Build.SUPPORTED_ABIS` order; `AndroidShellBinaries.resolve()` self-tests it first and falls back
to `/system/bin/sh`. `PATH` is the shim dir, then `/system/bin`. The exec probe
(`files/exec-probe.txt`) records all of the above from inside the app process, and now runs only
when `files/run-probe` exists (BACKLOG B-3).

Licence: bash is GPL-3.0-or-later; distributing the APK carries the standard source-availability
obligation. Record: `userland/PROVENANCE.md` + `scripts/build-bash-android.sh`.

