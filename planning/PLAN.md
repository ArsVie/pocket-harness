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

## Wave 0 — foundation (sequential, blocking)

| # | Task | Owner | Owns | Gate |
|---|---|---|---|---|
| W0.1 | Gradle scaffold: root `settings.gradle.kts`, `gradle/libs.versions.toml`, wrapper, `:core` (`kotlin("jvm")`, kotlinx-serialization, OkHttp, coroutines, JUnit5, MockWebServer, jacoco), `:app` (AGP, Compose, `compileSdk 36`, `targetSdk 28`, `minSdk 24`, `extractNativeLibs=true`), `local.properties`, `.gitignore`, `app/src/main/assets/presets/minimal.yaml` | subagent | all build files, `app/src/main/**`, `gradle/**` | `:core:test` green (placeholder test), `:app:assembleDebug` produces an APK, installed on `emulator-5554` |
| W0.2 | Freeze contracts: `core/src/main/kotlin/ph/**` interfaces + data types exactly as in `SPEC.md` §2, compiling, no logic | orchestrator | `core/src/main/kotlin/ph/**` | `:core:compileKotlin` green |
| W0.3 | Userland: busybox static arm64 in `userland/`, `rm` shim, licences + provenance + sha256, packaging into the APK, and an exec probe that proves the app can run the shell from the device | subagent | `userland/**`, probe Activity | `adb shell run-as <pkg> cat files/exec-probe.txt` shows busybox output on `emulator-5554` |

W0.3 depends on W0.1 (needs an APK); W0.2 is independent of both and is the orchestrator's own work.

## Wave 1 — five parallel workstreams (contracts frozen)

| # | Task | Owns | Contents |
|---|---|---|---|
| W1.A | `ph.model` | `core/src/{main,test}/kotlin/ph/model/**` | OpenAI chat-completions client (non-streaming), request-body shape, `reasoning_effort` validation, retry/backoff, error classification, DTOs, `SecretStore`-backed auth |
| W1.B | `ph.session` | `core/src/{main,test}/kotlin/ph/session/**` | JSONL log (header + contiguous `seq`), torn-tail repair, `SessionStore`, index cache + rebuild, summaries, mode re-derivation |
| W1.C | `ph.tools` | `core/src/{main,test}/kotlin/ph/tools/**` | `bash`, `str_replace_editor`, clipping + spill, `ToolOutcome` rendering, floor, trust gate, trash shim, dispatcher |
| W1.D | `ph.prompt` + `ph.agent` | `core/src/{main,test}/kotlin/ph/{prompt,agent}/**` | preset YAML loader, prompt assembly, deterministic pruning + context cap, linear loop, steering, stuck warning, abort |
| W1.E | `:app` + `ph.ui` | `app/**`, `core/src/{main,test}/kotlin/ph/ui/**` | `ThreadProjector`, thread list/thread view/settings in 2010 chrome, bubbles + tool rows + thinking blocks, mode switch, route settings, key storage (Keystore), foreground service, battery-optimization disclaimer, `Shell`/`ShellBinaries` implementations |

Rules for every workstream: no literal tunables (all numbers from `Preset`/`Budgets`/`LoopConfig`),
one test file per source file, no edits outside the owned prefix, no interface changes (raise them to
the orchestrator instead).

## Wave 2 — integration (orchestrator-led)

- Wire `:app` to `:core` end to end; delete the placeholder paths W0.1 created.
- Live path: a local OpenAI-compatible mock server reachable from the emulator, so the full loop
  (prompt → tools → transcript → threads UI) is exercised without spending real tokens.
- On-device: real `bash` through the shipped userland, `str_replace_editor` on a real workspace,
  trust prompt in DEFAULT, the same command in YOLO, floor denial, `rm` landing in `.trash/`.
- Persistence: kill and reopen the app mid-session; transcript and mode survive; a kill mid-turn
  closes with `INTERRUPTED`.
- Backgrounding: turn continues with the app backgrounded while the foreground service runs.

## Wave 3 — hardening and handoff

- Coverage audit: 100% line coverage on `:core`, dead/redundant code review, no `any`/`unknown`
  equivalents (Kotlin: no `Any`-typed parameters, no unchecked casts), complexity budgets.
- Real-endpoint smoke on the owner's phone (wireless adb) once it is on the same network as the build machine.
- Docs: `README.md` for the app, ADR status updates, `planning/README.md` status, this file updated
  to reflect what actually shipped.

## Risk register

| Risk | Status | Mitigation |
|---|---|---|
| Android W^X blocks exec from app storage | **open** — probe in W0.3 | `targetSdk 28` (Termux's path) is primary; `jniLibs` + `extractNativeLibs=true` is the fallback (ADR-001) |
| arm64 userland on an x86_64 emulator | **retired** — verified | NDK translation runs the static aarch64 busybox; evidence in `ENVIRONMENT.md` |
| Gradle/AGP/Kotlin version mismatches | **open** — W0.1 | the scaffold task's gate is a green build, so it must resolve versions empirically, not by assumption |
| Non-streaming turns feel inert | accepted | ADR-003; status line reports phase, not tokens |
| Real-endpoint behaviour differs from the mock | **open** — Wave 2 | mock is built from the same `SPEC` §2.2 wire contract; the real smoke runs before Wave 3 closes |
