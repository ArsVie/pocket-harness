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
| **arm64 execution on the x86_64 emulator** | **works** — `busybox` aarch64 static printed `HELLO_ARM64`, ran its `sh` (ash), and ran applets from symlinks | pushed `/data/local/tmp/busybox`, ran it |
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
| Gradle | distribution `gradle-9.1.0-bin` cached under the Windows `~/.gradle/wrapper/dists`; WSL cache is empty, the wrapper will download its own |
| Network from WSL | `dl.google.com` and `services.gradle.org` reachable |

Builds run on the Linux side (`/home/vruizes/projects/pocket-harness` is on the ext4 fs). The Windows
SDK is not used by the build; it is used for the emulator/AVD and as an adb source. Build-tools and
platform versions are deliberately identical (36 / 36.0.0) on both sides.

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
