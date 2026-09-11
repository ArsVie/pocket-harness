# PLAN — PocketHarness v1 build

Orchestration model: independent workstreams in parallel against frozen contracts (`SPEC.md`),
verification by the orchestrator at whole-wave merges only. Workstreams never verify each other and
never edit each other's files. File ownership is by path prefix, listed per task.

Gate commands (orchestrator runs these, not the workstreams):

```
./gradlew :core:test :core:jacocoTestReport :app:assembleDebug :app:lintDebug
```

plus, at the end of a wave, an on-device smoke the orchestrator performs personally.

---

## Wave 0 — foundation (COMPLETE, gate verified by the orchestrator)

| # | Task | Status | Evidence |
|---|---|---|---|
| W0.1 | Gradle scaffold | **done** | `:core:test`, `:app:assembleDebug`, `:app:lintDebug` all green; APK installs and launches on `emulator-5554` (1.2 s, window focused, no `AndroidRuntime` entries). Versions: Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.20, Compose BOM 2026.06.01, JUnit 6.1.3, JDK 21, JVM target 17. |
| W0.2 | Frozen contracts + shared fakes + tool schemas | **done** | `core/src/main/kotlin/ph/**` (compile-verified), `core/src/test/kotlin/ph/testing/Fakes.kt`, `ph/tools/ToolSchemas.kt` + golden test |
| W0.3 | Userland: busybox + shim + verifier | **done** | `userland/` (sha256 `e383c8bc…`), `scripts/test-userland.sh` 9/9, `scripts/mock-openai.py` selftest 7/7 |
| — | W^X exec path | **resolved** | exec from the app data dir at `targetSdk 28` verified on API 36 (see `ENVIRONMENT.md`); in-process proof is W1.E2's deliverable |

Known deviations from the original plan, recorded rather than smoothed over:

- The scaffold landed as `:core` + `:app` but the original Wave 1 split (five workstreams) was cut
  finer into **eight** when the scaffold child timed out *after* writing its files but before
  verifying them. A ~600 s child budget covers one module plus its tests plus one build; "scaffold AND
  device verification" was too much for one child.
- `app/build.gradle.kts` disables exactly one lint check (`ExpiredTargetSdkVersion`, a Google Play
  policy) because `targetSdk 28` is the ADR-001 decision. Every other check remains fatal.
- The scaffold's three-clause persona was replaced with the reference's single sentence; device
  guidance moved into `bash`'s description, which is where the research puts it. The tool schemas are
  orchestrator-owned so the request prefix cannot drift.

## Wave 1 — eight parallel workstreams (dispatched)

Each has exact file-path ownership and a fixed interface contract; none may edit a frozen file or
another stream's paths. Briefs in `WAVE1-BRIEFS.md`.

| # | Stream | Owns | Contract |
|---|---|---|---|
| W1.A | `ph.model` | `model/OpenAiClient.kt` | SPEC §2.2, ADR-003 |
| W1.B | `ph.session` | `session/JsonlSessionLog.kt`, `session/FileSessionStore.kt` | SPEC §2.3 |
| W1.C1 | tools: bash + dispatcher + floor | `tools/BashTool.kt`, `OutputClipper.kt`, `DefaultToolDispatcher.kt`, `DefaultPolicyFloor.kt` | SPEC §2.4, ADR-002/004 |
| W1.C2 | tools: editor | `tools/StrReplaceEditorTool.kt` | SPEC §2.4 |
| W1.D1 | `ph.prompt` | `prompt/YamlPresetLoader.kt`, `DefaultPromptAssembler.kt` | SPEC §2.5, §3 |
| W1.D2 | `ph.agent` | `agent/LinearAgentRunner.kt` | SPEC §2.6 |
| W1.E1 | `ph.ui` | `ui/DefaultThreadProjector.kt` | SPEC §2.7 |
| W1.E2 | `:app` + platform | `app/**` | ADR-005, ADR-001 |

Merge rule: the orchestrator reads the tree against `SPEC.md`, runs
`./gradlew :core:test :core:coverageGate :app:assembleDebug :app:lintDebug`, and reproduces the
on-device evidence personally. Child reports are not evidence.

## Wave 2 — integration (orchestrator-led)

Status: **substantially done and verified on device.**

| Item | State |
|---|---|
| `:app` wired to `:core` (real thread list, projector-driven thread view, Send/Stop/steer, settings→route, approval dialog) | **done** — `AppGraph`, `AppViewModel`, `AndroidSecretStore` (real Keystore AES-GCM), `FileTrustStore` |
| Full loop against a **real** provider | **done** — `deepseek/deepseek-v4.1-flash` via `https://api.commandcode.ai/provider/v1`, model id echoed, `AndroidRuntime:E` empty |
| Real tool execution on device | **done** — `tool_result isError:false` with real command output; `proof/marker.txt` created and read back as `real` |
| Trust gate in DEFAULT, approval, resume | **done** — denial → `approval_decided granted=true` → the same command then executes |
| Wire contract on real traffic | **done** — mock conformance checker reports `{"violations": []}`; provider itself reports cache hits (256/413 prompt tokens) |
| `bash` userland | **changed by evidence** — the platform shell (mksh + toybox), not busybox; see `ENVIRONMENT.md` |
| Foreground service + battery disclaimer | **in progress** |
| Real-endpoint turn: first-run seeding | debug-only bootstrap (`DebugEnvBootstrap`, gated on `BuildConfig.DEBUG`); must be removed before release |

Reachability, settled by experiment rather than assumption:

| Attempt | Result |
|---|---|
| `http://10.0.2.2:8111` from the emulator, mock bound in WSL | **works** — WSL2 mirrored networking makes the emulator's host alias reach the WSL listener |
| `adb reverse tcp:9111 tcp:8111` | fails — the reverse tunnel terminates at the machine running the adb **server** (Windows), so it never reaches the WSL mock. Verified: `--list` shows the rule, the device gets accept-then-EOF, and the mock's `served` counter does not move. |
| `http://<wsl-lan-address>:8111` (each of the WSL eth addresses) | fail — the emulator's NAT cannot route to WSL's LAN addresses |

The mock endpoint also *checks conformance* of every request against SPEC §2.2 (top-level field order,
forbidden vendor fields, persona-first, `tool_call_id` presence, the reasoning-replay rule) and exposes
the result at `/_violations` — a real provider will not tell you that you sent a field you should not
have.

### Workspace layout (settled, implemented)

The loop needs a `cwd` per session, and it is `filesDir/workspace/<session-id>/`, created on first use,
with `.trash/` and the spill directory inside it. Confirmed against the tools: `BashTool` derives its
spill directory from `ctx.cwd`, `AndroidTrashPolicy` derives the trash directory from the same cwd, and
the `rm` shim receives it as `PH_TRASH_DIR`. Live transcripts show commands resolving relative paths
inside that workspace.


## Wave 3 — hardening and handoff

- [ ] Foreground service + battery-optimization disclaimer (in progress).
- [ ] Remove test scaffolding: `Fixtures.kt` is dead code once the screens read real state; `ExecProbe`
      writes `files/exec-probe.txt` on every launch and should be gated behind a trigger file (it is
      the reproduction for the seccomp finding, so keep the code, stop running it unconditionally);
      `DebugEnvBootstrap`/`DebugWireLogger` are `BuildConfig.DEBUG`-gated and must not survive into a
      release build.
- [ ] Complexity / dead-code / mutation audit over `:core`.
- [ ] Real-endpoint smoke on the owner's physical phone over wireless adb. If the device cannot be
      reached, the fallback is to build the APK and install it manually — the app needs no adb at
      runtime, but a fresh install has **no API key**: it must be typed into Settings once (the
      debug bootstrap's `files/debug-env.json` path needs adb, so it is not available on a phone
      without adb).
- [ ] `:core` seams that should be promoted rather than duplicated in the app: `SessionStore.create`
      generates its own id (the app builds `JsonlSessionLog` directly to key a workspace on it), and
      `TrashPolicy` ships in `:core` only as a test fake, so the app has its own.

### Publish state

Published by the `ArsVie` account. The history was rewritten before the first push, so every commit
is authored and committed by `ArsVie`, and no pre-push object is reachable from the published refs.
`LICENSE` is MIT under the holder `ArsVie`. `userland/` carries a GPL-2.0-only busybox — see the
licence section of the README.

`python3 scripts/check-secrets.py` scans the build artefact *and* every blob in git history for key
material; run it before any push and again after any history rewrite. It reports OK.

## Risk register

| Risk | Status | Mitigation |
|---|---|---|
| Android W^X blocks exec from app storage | **open** — probe in W0.3 | `targetSdk 28` (Termux's path) is primary; `jniLibs` + `extractNativeLibs=true` is the fallback (ADR-001) |
| arm64 userland on an x86_64 emulator | **retired** — verified | NDK translation runs the static aarch64 busybox; evidence in `ENVIRONMENT.md` |
| Gradle/AGP/Kotlin version mismatches | **open** — W0.1 | the scaffold task's gate is a green build, so it must resolve versions empirically, not by assumption |
| Non-streaming turns feel inert | accepted | ADR-003; status line reports phase, not tokens |
| Real-endpoint behaviour differs from the mock | **open** — Wave 2 | mock is built from the same `SPEC` §2.2 wire contract; the real smoke runs before Wave 3 closes |
