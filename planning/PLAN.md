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
