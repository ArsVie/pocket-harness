# PocketHarness

A coding agent that runs on your phone. Real shell (bundled GNU bash 5.3), real files, a real
model over an OpenAI-compatible endpoint — presented as messaging threads in 2010-era Android chrome.

It is deliberately small. Two tools, a one-sentence system prompt, a linear loop, and the session
transcript as the durable object. The design is the result of a research pass over [Pi][pi], the
DeepSeek harness's `minimal` agent preset, and arXiv:2609.00006v1 (*Harness Engineering*); every
decision below is recorded with its sources in [`planning/`](planning/), and the ones that were ours
rather than theirs are marked as such.

## Status

Working end to end on an emulator (API 36, arm64/x86_64): a real turn against a real provider, tool
calls executing real commands in the session workspace, an approval gate in front of execution,
reasoning captured and replayed, transcripts persisted and replayed. See "What works" and "What does
not" below — both are kept honest, and the gaps are listed rather than implied.

## Design

The shape comes from three sources converging on the same answer:

| | This project |
|---|---|
| **Tools** | exactly two — `bash` and `str_replace_editor`. Read, search, write, edit and run are all *arguments of an existing schema* rather than new schemas. |
| **System prompt** | one sentence. Everything the model needs to know about the device lives in the tool descriptions, which co-vary with the toolset. If the prompt ever needs to grow, that is a signal the tools are under-described. |
| **Loop** | `while (the model wants tools ∨ input is queued)`, hand-rolled. No planner, no middleware, no framework. |
| **Session** | one append-only JSONL per thread: header + contiguous `seq` events. The transcript is the artifact; resume is a replay. |
| **Safety** | a policy floor that outranks the mode switch, `rm` mapped to trash, and a per-folder trust gate. No OS sandboxing — a deliberate scope decision, recorded. |
| **Wire** | non-streaming `POST {baseUrl}/chat/completions`, `reasoning_effort` as the only reasoning lever. |

Decisions and their reasoning:

| ADR | Decision |
|---|---|
| [0001](planning/decisions/0001-execution-surface.md) | On-device execution (Termux-style): tools are a real shell over a real filesystem |
| [0002](planning/decisions/0002-safety-floor.md) | Floor-level safety only, `rm` → trash, deferred items named explicitly |
| [0003](planning/decisions/0003-model-wire.md) | Non-streaming wire, `reasoning_effort` as data, reasoning replayed only on tool-call turns |
| [0004](planning/decisions/0004-execution-modes.md) | DEFAULT/YOLO switch beneath a frozen floor |
| [0005](planning/decisions/0005-app-shell.md) | Three screens, thread queue, foreground ownership of a turn |
| [0006](planning/decisions/0006-shell-userland-bash.md) | GNU bash 5.3 built for Android ships as the userland; the platform shell is the runtime fallback |

`planning/ENVIRONMENT.md` records what the platform actually permits, measured rather than assumed —
including the finding that Android's seccomp filter on the app process blocks a static (musl) busybox
from dispatching applets, and the follow-up that a GNU bash 5.3 built against bionic sails through
the same filter in the same process. The harness runs bash; the platform shell is the fallback.

## Layout

```
core/       pure Kotlin/JVM — all logic. No Android imports. Fully unit-tested without a device.
  ph/model      OpenAI-compatible client, errors as values, retry/backoff
  ph/session    append-only JSONL log, torn-tail repair, rebuildable index
  ph/tools      bash + str_replace_editor, output clipping/spill, dispatcher, policy floor
  ph/prompt     preset loading, prompt assembly, deterministic pruning and context cap
  ph/agent      the linear loop, steering, stuck detection
  ph/ui         pure event → UiState projection (no Compose)
  ph/ports      the interfaces the Android shell implements
app/        Compose UI, foreground service, Keystore, and the platform bindings
userland/   the rm→trash shim + hashes and provenance for the shipped shell
scripts/    mock endpoint, shell build/probe tooling, secret checker
planning/   research briefs, ADRs, spec, plan, environment record
```

The `:core` / `:app` split is the point: the entire loop, tool layer, prompt assembly and persistence
run under plain JVM tests, so a change that compiles is a change that is verified. `:app` renders
`UiState` and owns nothing.

## Build and run

Requires JDK 17+ and an Android SDK with platform 36 and build-tools 36.0.0.

```bash
export ANDROID_HOME=$HOME/android-sdk
./gradlew :core:test            # all logic, no device
./gradlew :core:coverageGate    # reports line coverage, fails below 95%
./gradlew :app:assembleDebug    # the APK
./gradlew :app:lintDebug
```

Install and launch:

```bash
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.arsvie.pocketharness/.MainActivity
```

Then open **Settings** and enter your endpoint's base URL, model id and API key. The key goes to the
Android Keystore; nothing else stores it. `reasoning_effort` options come from the route's advertised
list, so a value the endpoint does not accept fails before the request is sent.

### Trying it without a model

`scripts/mock-openai.py` is a scripted OpenAI-compatible endpoint that also *checks conformance* of
every request against the wire contract (top-level field order, forbidden fields, prompt shape, the
reasoning-replay rule) and reports violations:

```bash
python3 scripts/mock-openai.py --port 8111 --script scripts/mock-scripts/tool-loop.json
curl -s http://127.0.0.1:8111/_violations    # [] means the client stayed inside the contract
```

On an emulator, the host is reachable at `10.0.2.2`, so the app's base URL would be
`http://10.0.2.2:8111` — note this is plain HTTP, which is why the debug manifest permits cleartext.

## Tests

`./gradlew :core:test` runs the whole logic layer. Coverage is enforced at ≥95% by
`./gradlew :core:coverageGate`, which prints the number it measured; the frozen contract files (data
classes, sealed hierarchies, interfaces) are excluded from the denominator, implementation classes are
not. `scripts/test-userland.sh` verifies the shipped shell artifacts on the host, and
`scripts/check-secrets.py` scans a build artifact *and the whole git history* for key material — run it
before publishing anything.

## What works

- The full loop against a real OpenAI-compatible endpoint: prompt → tool call → real command →
  result → model summary, with reasoning captured.
- **GNU bash 5.3** runs inside the app process — seccomp filter and all — built with the NDK and
  shipped as per-ABI assets; the platform shell remains the fallback (ADR-006).
- `bash` and `str_replace_editor`, output clipping with spill-to-file, exit codes, timeouts.
- Per-folder trust gate in DEFAULT mode with an approval prompt; YOLO skips the gate; the policy floor
  applies in both.
- Sessions as append-only JSONL: resume, replay, torn-tail repair, rebuildable index.
- Steer mid-turn (queued, delivered at the next step boundary), Stop, and a stuck-call warning.
- Tool descriptions rewritten for the device the model is actually on — no `apt`, and the toybox gaps are stated rather than assumed.

## What does not, yet

- **A turn does not reliably survive backgrounding** — the foreground-service work is the open item.
  That service, with its persistent notification, is what keeps a turn alive: a battery-optimization
  exemption was considered and deliberately cut, because it matters chiefly for work with no
  foreground presence, which this app does not attempt (ADR-005 §6).
- **No token streaming** by design (ADR-003): a turn appears when it completes, with a status line
  while it runs.
- Deferred on purpose, not pending: multi-agent, MCP, skills, plan mode, todo tools, code RAG/embeddings,
  a cost meter, dark mode, i18n, session forking, OS sandboxing, Play distribution.

## Licence

Harness code: MIT (see `LICENSE`).

`app/src/main/assets/userland/bash-*` contain **GNU bash 5.3**, built from the GNU sources with the
NDK; bash is **GPL-3.0-or-later**, so redistributing this APK carries the usual source-availability
obligation — the build recipe is `scripts/build-bash-android.sh` and the upstream source lives at
`https://ftp.gnu.org/gnu/bash/`. The `rm` shim and everything under `core/` and `app/` are original
work.

[pi]: https://pi.dev
