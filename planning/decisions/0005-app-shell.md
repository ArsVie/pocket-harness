# ADR-PH-005 — App shell: three screens, thread queue, background survival

| Status | Accepted |
|---|---|
| Date | September 2026 |
| Author | Ars (decision), Hermes (record) |
| Supersedes | — |

## Context

The interface layer's shape was fixed by correction in Part 2 (see "Decisions recorded verbatim"
below). The remaining engineering question is what the app *is* while a turn is running: Android
will happily freeze and kill a backgrounded app mid-turn, and a coding harness whose loop dies when
the screen locks is not usable on a phone.

Decision recorded verbatim, as given:

> And for the UI/App, a disclaimer to turn off battery optimizations to not let the app die on
> background.
>
> 4. Correct, mid turn sends will be put in query.
> 3. Correct, and no token output streaming.

## Decision

1. **Three screens, no more:** thread list → thread view → settings. 2010-era Android settings
   chrome (grey gradient title bar, thin row dividers, small sans text, tall list rows,
   checkbox/switch preference rows), with message bubbles rendered *inside* that chrome. One fixed
   theme, English only, no dark-mode toggle, no i18n.
2. **No token streaming anywhere.** Assistant text appears as bubbles when the turn completes.
   Turn progress is shown as a status line, not as partial text.
3. **Tool calls and reasoning are not bubbles.** One collapsed row per tool call (`bash: ls -la`)
   that expands to monospace output; one collapsible "Thinking" block per assistant turn. Only user
   and assistant text is a bubble.
4. **Mid-turn input is queued, not rejected.** Send is always enabled. A message sent while a turn
   is running is appended to the thread immediately, drawn as a queued bubble, and delivered into
   the running turn at the next step boundary (reference semantics: `steer` → next step). Stop is a
   separate control and aborts the turn controller.
5. **A turn survives backgrounding.** A foreground service with a persistent notification owns the
   active turn; exactly one active turn at a time. If the service is still killed despite that, the
   turn ends as `interrupted` on resume (synthetic turn end; the transcript is never truncated
   mid-turn).
6. **A first-run disclaimer** states plainly that the app must be exempted from battery
   optimization to keep running in the background, with a button that opens the system's
   battery-optimization settings page for this app. It is shown once and re-openable from Settings;
   it is a disclaimer, not a blocking permission gate.
7. **Session persistence is files, not a database:** one append-only JSONL per session (header +
   contiguous `seq` events) plus a small index for the inbox. No Room, no SQLite, unless measured
   list rendering proves it necessary.
8. **`minSdk`/`targetSdk` 28**, arm64-v8a only, sideloaded debug APK, no release signing, no Play
   distribution constraint. Play policy is explicitly not a design constraint (ADR-001).

## Consequences

**Upside.** No streaming assembler, no partial-message state machine, no diffing renderer; the
transcript on screen is a pure projection of the log. The queue gives the user a way to correct a
running turn without a second turn.

**Downside.** A long turn looks inert. The foreground notification is a permanent-while-running
artifact on the phone; that is the price of the loop surviving a pocket.

## Implementation gaps (open)

- Whether the foreground service outlives the process (START_STICKY) or is tied to the Activity's
  lifetime plus a wake lock.
- Whether the inbox index is a JSON file or a single flat directory + filename convention.
- Exact copy and placement of the battery disclaimer (first-run only vs. every N runs).

## References

- ADR-001 (execution surface, targetSdk 28), ADR-002 (floor), ADR-003 (non-streaming wire),
  ADR-004 (modes).
- `../research/01-pi-harness.md` (session transcript as the durable artifact), `../research/
  02-deepseek-harness-minimal.md` §10.5 (steering trio), `../research/03-arxiv-harness-engineering
  .md` §10 item 9 (messaging threads as the session substrate).
