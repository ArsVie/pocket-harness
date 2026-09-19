# BACKLOG

Known, real, and deliberately deferred. Each entry has the evidence needed to pick it up cold, and
none of it blocks the current PoC. Ordered by what would bite first.

---

## B-1 — Foreground service can crash the app on a fast-failing turn

**Severity: high when it fires** (the app dies mid-turn), but it is not deterministic, and a normal
turn is unaffected.

Observed on the owner's phone (Redmi 24117RN76G, Android 16, arm64):

```
FATAL EXCEPTION: main  Process: com.arsvie.pocketharness, PID: 31156
android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException:
    Context.startForegroundService() did not then call Service.startForeground():
    ServiceRecord{7f53f39 u0 com.arsvie.pocketharness/.TurnService}
    at android.app.ActivityThread.generateForegroundServiceDidNotStartInTimeException(...)
```

That trace was captured while the endpoint was returning `400`s, i.e. while turns were failing in
well under a second. A later, successful multi-step turn (3 tool round-trips) ran with no crash. So
the trigger is not "the service is broken" but something about the timing of a short-lived turn.

**Leading hypothesis — a start/stop race.** `AppViewModel.startTurn()` calls `TurnService.start(app)`
and the `finally` block calls `TurnService.stop(app)`. When the turn fails almost immediately, the
stop can reach the service before its `onStartCommand` has run `startForeground()`; the service record
still owes that call, and the system raises the exception. A turn that lasts long enough never races.

**Second hypothesis.** Process/`main`-thread contention during a cold start delays `onStartCommand`
past the deadline (the app does provisioning work — asset unpack, shell self-test — at startup).

**To do:** make the service robust to both. Options, cheapest first:
1. Have the service set a "reached startForeground" flag (or use `stopSelf()` driven from inside the
   service) and make the companion's `stop()` a no-op when the service never got there.
2. Debounce: do not call `stop()` within the same second as `start()`; let `onStartCommand` win.
3. Start the service lazily — on the first `ToolStarted` event — rather than the instant a turn is
   submitted, so a turn that dies before any tool never starts it at all.
4. Also worth measuring: whether `foregroundServiceType="dataSync"` is the right type (Android 16
   enforces declared types more strictly), and whether the notification needs
   `POST_NOTIFICATIONS` handling on 13+.

**Reproduce:** force the endpoint to fail fast (point the route at a closed port) and send a message;
compare with a healthy endpoint. Evidence lands in `files/sessions/<id>/session.jsonl` plus
`adb logcat -s AndroidRuntime:E`.

---

## B-2 — Transcripts written before the reasoning fix cannot be replayed

The provider requires the reasoning of a tool-call turn to be sent back with it
(`The reasoning_content in the thinking mode must be passed back to the API`). Older transcripts in
`files/sessions/` never logged that reasoning for tool-call turns — the text was dropped at log time —
so every subsequent turn in those threads fails with `400`. New threads are unaffected.

**To do:** either mark such a thread read-only with a clear reason in the UI, or accept it and note it
in the README. There is no way to repair it: the text was never recorded.

---

## B-3 — Test scaffolding still in the app

Remove or gate before any release build:

- `DebugEnvBootstrap` / `DebugWireLogger` — `BuildConfig.DEBUG`-gated, but must not exist in release.
  They are how the API key got onto the device without typing 93 characters.
- `ExecProbe` — ~~writes `files/exec-probe.txt` on every launch~~ **gated in v2**: it runs only when
  `files/run-probe` exists. Still debug scaffolding; its sections now cover the bash experiments
  (ADR-006). Keep the trigger discipline for release builds.
- `ui/Fixtures.kt` — dead: the v2 screens read real state (ADR-007). The file is unreferenced now;
  delete it with the next `:app` change.

## B-4 — `:core` seams that the app should not be re-implementing

- `SessionStore.create(cwd, presetId)` generates the id internally, so a caller cannot key
  `workspace/<session-id>/` on it before the session exists; `AppGraph` builds `JsonlSessionLog`
  directly to work around this. Promote an explicit-id create.
- `TrashPolicy` exists in `:core` only as a test fake, so the app has its own `AndroidTrashPolicy`.
  Promote it to a real `:core` type with an injected base path.

## B-5 — Cleartext HTTP is permitted in the debug manifest

Needed for the local mock endpoint over `http://10.0.2.2:8111`. Harmless for HTTPS endpoints, but it
should be scoped to a debug-only manifest (or a network security config limited to `10.0.2.2`) rather
than carried into anything shipped.

## B-6 — No cost or usage surface

`usage.cached_tokens` and `usage.completion_tokens_details.reasoning_tokens` are already parsed and
were observed on real traffic (256 of 413 prompt tokens cached on a repeat request). Nothing displays
them. A one-line per-turn token/cache counter in the thread header would make the cache behaviour
visible, which is the whole point of the stable-prefix design.

## B-7 — Runtime context is suppressed and the model compensates

`includeRuntimeContext: false` is the reference preset's choice and it is kept. The consequence is
that the model does not know its working directory until it runs `pwd`; the `bash` description now
tells it to. If long sessions show it fumbling paths, the knob to revisit is the preset flag, not the
persona.

## B-8 — YOLO semantics are unspecified beyond "skip the trust gate"

`ExecutionMode` is per-session (re-derived from the log). Whether YOLO should also be per-project, and
whether a running turn keeps the mode it started with, is undecided. Currently a turn uses the mode
captured at its start.

## B-9 — Trash is unbounded

`rm` moves into `<workspace>/.trash/<timestamp>-<name>` and nothing ever prunes it, including by age
or size. Fine for a PoC; a cap and a Settings row ("trash: N items, M MB — empty") would be the
minimum for anything longer-lived.

## B-10 — Stop / mid-turn steering still unexercised on a real turn

Both are wired (Stop → `AgentRunner.stop()`, mid-turn send → `steer()` plus a queued bubble) and unit
tested, but no live turn has exercised either. Cheap to check on a slow turn.

---

## B-11 — After an approval, the denied call itself is never retried

The dispatcher denies a call whose cwd is untrusted (`"…is not trusted; approve this folder first"`),
the loop interrupts the turn, the user taps Allow, trust is persisted to `files/trust.json`, and the
turn resumes — but the denied call is dead: the model only ever saw the error, never learns the folder
was approved, and improvises elsewhere. Observed live (v2 UI session, `session-4a8becbb`): after
Allow it re-ran the command under `/tmp`, which was already trusted, instead of the just-approved
folder.

**To do:** on Allow, either re-run the denied call, or append a synthetic note (folder `<cwd>` is now
trusted) before resuming. Cheap; verify on a live turn. Evidence: the `session-4a8becbb` transcript
on the emulator, `review/ui-after/07-…` and `11-…` screenshots.

---

## Resolved (kept here so they are not re-litigated)

- **W^X / `targetSdk 28`** — resolved; exec of app-data files works. See `ENVIRONMENT.md`.
- **busybox userland** — dead end; the platform shell (mksh + toybox) is the userland, because the
  app process carries a seccomp filter that a static musl binary cannot survive. `ENVIRONMENT.md`.
- **`reasoning` vs `reasoning_content`** — accept both; this provider uses `reasoning`.
- **`reasoning_effort` level set** — `low|medium|high|xhigh|max`, discovered from the provider's own
  validation error. Data, not a constant.
- **Emulator ↔ mock reachability** — `http://10.0.2.2:8111` works (WSL2 mirrored networking);
  `adb reverse` does not reach WSL. `PLAN.md`.
- **Battery-optimization disclaimer** — cut by the owner mid-build; the foreground service is what
  keeps a turn alive. `0005-app-shell.md` §6.
- **bash userland** (v2) — GNU bash 5.3 built with the NDK ships as per-ABI assets and is preferred
  at runtime; the platform shell is the fallback. `0006-shell-userland-bash.md`, ENVIRONMENT.md.
- **getcwd stderr noise** (v2) — fixed at configure (`bash_cv_getcwd_malloc=yes`), not with an
  `export PWD` band-aid. `0006-shell-userland-bash.md`.
- **UI v1 chrome** (v2) — the 2010-era settings chrome and the always-on tab strip were superseded
  by the pastel light theme and push navigation. `0007-app-shell-v2-ui.md`; before/after screenshots
  under `review/`.
