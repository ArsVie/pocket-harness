# WAVE 1 BRIEFS — five parallel workstreams

Per-workstream task text for `delegate_task`. The orchestrator spawns all five at once, with the
scaffold (Wave 0) already merged and its gate green. Each child gets: the shared preamble below, then
its own brief. Everything else lives in `planning/SPEC.md` — children read it rather than being told.

## Shared preamble (prepend to every brief)

```
Project: PocketHarness at /home/vruizes/projects/pocket-harness — an Android coding harness
(OpenAI-compatible model client + on-device shell tools + messaging-thread UI).

READ FIRST, in this order: AGENTS.md, planning/SPEC.md (the frozen contract), planning/ENVIRONMENT.md.
Your section of SPEC.md §2 is the contract you implement; the .kt files under core/src/main/kotlin/ph/
are its machine-checked form.

Environment:
- WSL2 Linux, JDK 21. Build from /home/vruizes/projects/pocket-harness with `./gradlew`.
- ANDROID_HOME=/home/vruizes/android-sdk (already installed: platform 36, build-tools 36.0.0).
- Gradle runs are serialised ACROSS AGENTS: always run Gradle through the lock, e.g.
    flock /tmp/ph-gradle.lock -c './gradlew :core:test --tests "ph.model.*"'
  Never run Gradle without the lock — four other agents share this project right now.
- Add `--tests "ph.<yourpackage>.*"` so you only run your own tests.

Hard rules (from AGENTS.md, restated because they are gating):
- `:core` is kotlin("jvm"). Never import android.* or AndroidX there.
- Interfaces under core/src/main/kotlin/ph/** are FROZEN. Do not change a signature, add a member, or
  rename anything. If you believe the contract is wrong, STOP and report it — do not work around it.
- No literal tunables. Every timeout/cap/threshold/retry count comes from `ph.prompt.Budgets`,
  `ph.prompt.LoopConfig`, or `ph.prompt.Preset`. A bare number in an implementation file is a defect.
- Errors are values: `ToolOutcome.Err` / `ModelOutcome.Failure`, never thrown to the caller.
- One test file per source file, next to it under core/src/test/kotlin/ph/<same path>. Line coverage on
  the files you write must be 100% — measured, not asserted.
- Do NOT create a FakeShell/FakeModelClient outside your own package if another brief owns it — see
  your brief; use your own local test doubles.
- Do not run `git commit`. Do not create files outside your owned paths.
- Do not write planning/ docs. The orchestrator owns documentation.

Report back with: files created (paths), the exact Gradle command(s) you ran and their result, coverage
number for your files, any interface friction you hit, and anything you could NOT do. If a test is
failing or a number is unmeasured, say so plainly — a false "done" costs the wave a re-run.
```

## W1.A — `ph.model` (owns `core/src/{main,test}/kotlin/ph/model/**`)

Implement the OpenAI-compatible client against SPEC §2.2 and ADR-003. Deliverables:

- `OpenAiClient` implementing `ModelClient`: one non-streaming `POST {baseUrl}/chat/completions`,
  `Authorization: Bearer`, body serialised with the exact top-level order `model`, `messages`, `tools`,
  `reasoning_effort` (`reasoning_effort` omitted entirely when null; `stream` never present).
- Validation **before any I/O**: unknown `reasoningEffort` → `UNSUPPORTED_REASONING_EFFORT`;
  missing/blank key from `SecretStore` → `MISSING_CREDENTIAL`; `!route.supportsTools` while tools are
  requested → `NO_TOOL_SUPPORT`.
- Retry: at most `LoopConfig.modelRetries` retries on 429/5xx/transport errors, sleeping
  `retryBackoffMs` in order; never retry other 4xx. 401/403 → `AUTH_REJECTED`.
- Response parsing: `content`, `reasoning_content` else `reasoning`, `tool_calls[].function.arguments`
  kept as the raw string, `usage`, `prompt_tokens_details.cached_tokens`; a body that parses but has no
  `choices` → `MALFORMED_RESPONSE`.
- `SecretStore` and `Clock` are injected; the KeyStore-backed implementation is NOT yours (W1.E owns
  the Android side of it).

Tests must cover, at minimum: the serialised request body byte-shape (assert the raw JSON string, not a
re-parsed object — field order is the point), every `ModelErrorCode`, the retry/backoff sequence
including "retries exhausted", reasoning parsing from both field spellings, tool-call argument strings
round-tripping unchanged, and usage extraction with and without `prompt_tokens_details`.

## W1.B — `ph.session` (owns `core/src/{main,test}/kotlin/ph/session/**`)

Implement the append-only JSONL log and store per SPEC §2.3. Deliverables:

- `JsonlSessionLog`: `<root>/<id>/session.jsonl`, line 1 the header, then one `{seq,time,type,data}`
  envelope per event; `seq` contiguous from 0, assigned on append, flushed per event.
- Torn-tail repair on open: a final partial/unparsable line is discarded and an open turn is closed
  with `TurnEnd(INTERRUPTED)`; a complete open turn is never truncated.
- `FileSessionStore`: `create`/`open`/`list`/`delete`, `SessionSummary.title` = first user message
  truncated to 60 chars else the id, `list()` newest first.
- `index.json` as a rebuildable **cache**: `list()` must rebuild it by scanning session directories when
  it is missing or corrupt, and deleting it must lose nothing (this is a required test).
- `resolveMode(events, header)`: scan backwards for the last `ModeSelected`, else the preset default —
  never trust the header alone.

Tests: seq contiguity after many appends, header round-trip, every event type surviving a
write/read/reopen cycle unchanged, torn-tail repair (write a truncated line by hand), mode
re-derivation, index rebuild from a wiped index, list ordering, and delete removing the directory.

## W1.C — `ph.tools` (owns `core/src/{main,test}/kotlin/ph/tools/**`)

Implement the two-tool surface per SPEC §2.4. Deliverables:

- `BashTool` (`bash`) and `StrReplaceEditorTool` (`str_replace_editor`) with byte-stable schemas:
  field order as SPEC lists it, serialised once.
- `BashTool`: runs through the injected `ph.ports.Shell`, `Budgets.commandTimeoutMs` timeout, non-zero
  exit appends `[exit code: N]`, timeout → `Err(TIMEOUT, …)`, output clipped to `maxOutputLines` /
  `maxOutputChars` head+tail with the remainder spilled to a file and `[truncated: full output at …]`
  appended.
- The `bash` description is where this harness's guidance lives (the persona is one sentence on
  purpose — see the preset's header comment and research 02 §10.2). Write it for a phone, and keep it
  the reference's bullet shape: state is persistent across calls; **no internet access, no package
  manager — `apt`, `pip`, `npm` are not available** (the reference text claims a Linux package mirror
  and is factually wrong on this device); search with `grep -rn`, read ranges with `sed -n A,Bp`,
  inspect line counts with `wc -l` before dumping a file; avoid commands that produce large output;
  run long-lived work in the background; **run the thing before claiming it works, and report a
  failure as a failure**; `rm` moves to `.trash/`, not to oblivion. This description is model-visible
  prose and part of the byte-stable prefix, so it is authored once and never edited per turn.
- Policy in `DefaultToolDispatcher`: the `PolicyFloor` check for `bash` in **both** modes →
  `Err(DENIED, "…<rule>…")`; DEFAULT mode with an untrusted cwd → `Err(DENIED, …)` (the loop, not the
  tool, raises the prompt); `UNKNOWN_TOOL` for anything else. Tool arguments that are not valid JSON, or
  missing a required field, → `Err(BAD_ARGUMENT, …)` naming the field.
- `StrReplaceEditorTool` semantics exactly as SPEC §2.4 lists (absolute-path teaching error, `view`
  with 6-wide line numbers and 2-level directory listing excluding dotfiles, `create` refusing an
  existing file, `str_replace` requiring a unique `old_str` and naming conflicting lines, `insert`
  after `insert_line` with 0 = start) and the exact `ToolErrorCode` values.
- Filesystem access goes through `java.io.File` in `:core`; the workspace root is passed in via
  `ToolContext.cwd`. No Android APIs.

Use your own in-package `FakeShell` (a `Shell` that records commands and returns scripted
`ExecResult`s). Tests: clipping arithmetic at the exact boundaries (lines and chars), spill file
contents, exit-code tail, timeout, every error code, floor denial in DEFAULT and YOLO, trust gate,
every editor command and every editor error, and a golden test of the two schemas' serialised JSON.

## W1.D — `ph.prompt` + `ph.agent` (owns `core/src/{main,test}/kotlin/ph/{prompt,agent}/**`,
`app/src/main/assets/presets/minimal.yaml`, and may add exactly one dependency line to
`core/build.gradle.kts`)

Implement prompt assembly, preset loading, pruning and the linear loop per SPEC §2.5–2.6. Deliverables:

- `YamlPresetLoader` reading `presets/minimal.yaml` into `Preset`, failing loudly (a thrown
  configuration error is correct here — this is startup, not a model-visible path) on a missing or
  unparsable file, and never silently falling back to defaults.
  Dependency: add `implementation("org.yaml:snakeyaml:2.3")` (verify the newest 2.x that resolves) to
  `core/build.gradle.kts` — that file is otherwise owned by the scaffold, so change only that line.
- `DefaultPromptAssembler`: persona as the entire system message (`complete = true`; nothing appends,
  and with `includeRuntimeContext = false` no cwd/date/platform message exists at all), history
  projection in event order, `reasoning` replayed **only** on assistant turns that carried tool calls,
  steering appended at the consumption point.
- Deterministic pruning: clip tool results older than the last `verbatimToolResults` to
  `pruneHeadChars`+`pruneTailChars` with a `[pruned N chars]` marker, only when their total exceeds
  `pruneThresholdChars`; then, above `contextCapTokens` (estimated `chars / charsPerToken`), drop whole
  oldest turns — never the first user message, never the newest turn — reporting `prunedSeqs`.
- `LinearAgentRunner` implementing `AgentRunner`: `while (model asks for tools || steering pending)`,
  appending every model call and tool result to the session **before** using it; tools dispatched in
  model order; `toolCallsPerStepCap` overflow getting `Err(BAD_ARGUMENT)` results so every call has a
  result; stuck detection via sha256(name + canonicalised arguments) emitting one `Warning`; `stop()`
  aborting with `TurnEnded(ABORTED)`; `turnCap` (null = unlimited) producing `TurnEnded(POLICY_STOP)`;
  model failure appending `ModelFailure` then `TurnEnded(ERROR)`.

Use your own in-package `FakeModelClient` and in-memory `SessionStore` (do not import another
workstream's test doubles — do not touch `ph.model`'s or `ph.session`'s test files). Tests: persona-only
system message, tool-call turn producing assistant+tool messages, reasoning replay rule both ways,
pruning thresholds at the boundary, cap drop order and its invariants, steering consumed at the step
boundary, cap enforcement, stuck warning (emitted once), abort path, and the model-failure path.

## W1.E — `ph.ui` + the Android app (owns `core/src/{main,test}/kotlin/ph/ui/**`, `app/**` except
the preset asset which is W1.D's)

Two halves.

**(1) `ThreadProjector`** in `:core` (SPEC §2.7): a pure function of events → `OpenThread`. Tool calls
become collapsed `ToolCall` blocks with a one-line summary (`bash: ls -la`) and monospace output on
expand; assistant text becomes `AssistantText`; reasoning becomes a collapsible `Thinking` block; a
user message sent mid-turn carries `queued = true`; `pendingApproval` is set from a live
`ApprovalNeeded`. No Compose imports, fully unit-tested (every block type, ordering, running status
line, approval, mode rendering).

**(2) The app**: Android with Compose, 2010-era settings chrome — grey gradient title bar, thin row
dividers, small sans text, tall preference rows, checkbox/switch rows — and three screens only
(thread list / thread view / settings), per ADR-005. Concretely:

- Thread view: user and assistant text as bubbles; tool calls as collapsed expandable rows; thinking
  as a collapsed block; **no token streaming** — text appears when the turn completes; a status line
  while running (`Request sent…`, `Running bash…`, `Waiting for the model…`); Send always enabled, a
  mid-turn send rendering as a queued bubble, plus a separate Stop control.
- Settings: the DEFAULT/YOLO switch (one control), base URL / model / `reasoning_effort` picker (from
  the route's advertised list) / API key entry, project trust list with revoke, and a battery-optimization
  entry that re-opens the disclaimer.
- First-run disclaimer screen stating that the app must be exempted from battery optimization to keep
  running in the background, with a button opening the system battery-optimization settings page for
  this package, acknowledged once and re-openable from Settings.
- `AndroidShell` implementing `ph.ports.Shell`: unpacks `assets/userland/busybox` to
  `files/userland/busybox` (+x) on first run, deploys `userland/rm.sh` with `@SHELL@` substituted and
  the trash dir exported, runs commands with the shim directory first on `PATH`, per-command timeout
  from `Budgets`, stdout/stderr/exit code captured, and a real `kill` on timeout.
- `KeystoreSecretStore` implementing `ph.ports.SecretStore` (Android Keystore-backed, encrypted shared
  prefs), never logging the value.
- A foreground service with a persistent notification owning the active turn, and exactly one active
  turn at a time.

The app does not need a working end-to-end run yet (the orchestrator wires `:app` to `:core` in Wave
2) — but it must compile, `:app:lintDebug` must be clean, and the screens must be reachable with
`UiState` fixtures so the orchestrator can see them on the emulator.

Running on the emulator from this shell:

```
export ADB_SERVER_SOCKET=tcp:127.0.0.1:5037
export PATH=$HOME/android-sdk/platform-tools:$PATH
adb devices                                    # emulator-5554, API 36
flock /tmp/ph-gradle.lock -c './gradlew :app:assembleDebug'
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.arsvie.pocketharness/.MainActivity
adb -s emulator-5554 logcat -d | grep -i "FATAL\|AndroidRuntime"
```
