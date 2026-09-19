# ADR-PH-007 — App shell v2: pastel light theme, push navigation, inline approvals

| Status | Accepted |
|---|---|
| Date | September 2026 |
| Author | Ars (decision), Hermes (record) |
| Supersedes | ADR-005 §1 — the "2010-era Android settings chrome" clause (in part; see §9) |

## Context

ADR-005 §1 fixed the v1 look deliberately: 2010-era Android settings chrome — grey gradient title bar,
hairline rows, small sans text, tall preference rows — with an always-visible three-tab strip as the
navigation between thread list / thread view / settings. v1 shipped exactly that.

For v2 the owner asked for the opposite: a severely improved, modern, soft-pastel UI with real
navigation. Unlike the shell work (ADR-006), taste is not measurable, so the acceptance gate is the
owner reviewing screenshots: `review/ui-before/` (v1, five shots) and `review/ui-after/` (v2, live on
the emulator, twelve shots). Neither folder is committed until the owner says keep.

## Decision

1. **One fixed soft-pastel light theme; no dark mode** (the ADR-005 §1 single-themeclause stands).
   The palette lives only in `:app` (`ui/Theme.kt`): periwinkle primary, teal/rose accents, warm
   off-white background, white cards with hairline borders and 16 dp radii. Status is a **solid
   rounded square, never a colored dot**.
2. **Push navigation replaces the tab strip.** Threads list is home; tapping a row opens the thread;
   the gear opens Settings; a back arrow and the system back gesture return. No always-on tabs
   (`MainActivity` + `BackHandler`).
3. **The thread is a lazy list with bottom auto-follow** — it jumps to the latest block on open and
   follows new blocks only while the user is already at the bottom. Thinking and tool calls are
   collapsible rows: monospace summary/output, a status square (green = ok, periwinkle = running,
   red = error), **no raw call IDs**, and errors render the tool's real error text verbatim
   (observed live: the denied-folder message, red, readable).
4. **The approval is an inline permission card** in the thread (Deny / Allow) instead of a modal
   dialog; while it waits, a "Waiting for you" pill replaces "Running…". UI only — core mechanics
   are untouched: the dispatcher denies, the loop interrupts, Allow persists folder trust
   (`files/trust.json`), the turn resumes.
5. **The composer morphs Send ↔ Stop in one slot**; a queued steer message is drawn as a labeled
   queued bubble; the composer consumes `WindowInsets.ime ∪ navigationBars`, and the app sets
   `enableEdgeToEdge` + `statusBarsPadding` headers with `adjustResize` in the manifest.
6. **Settings is card sections** — Execution (two-option mode selector), Model (Base URL / Model /
   effort chips), Credentials (keystore status square), **Diagnostics**: the live shell, resolved at
   runtime — `GNU bash 5.3.0(1)-release` + path (the ADR-006 self-test made visible).
7. **Honest states throughout:** empty threads list, fresh-thread empty state, "not set" rows for
   missing config, Send disabled until there is text.
8. **One new pinned dependency:** `material-icons-core` 1.7.8 — the icons artifacts are frozen
   upstream and were dropped from the Compose BOM, so the version is pinned in the catalog.
9. **Scope of the supersede:** ADR-005 §1's chrome clause and the tab-strip reading of "three
   screens" only. §2 (no streaming), §3 (block rules), §4 (queue semantics), §5 (foreground
   service), §6 (cut disclaimer), §7 (files not a database), §8 (SDK pins) all stand.

## Consequences

**Upside.** The UI is usable one-handed and honest: navigation is real, the thread is scannable,
tool output is one tap away, and every state the harness can be in has a rendering. `:core` is
untouched — the UI is still a pure projection of `UiState`, verified by the existing tests.

**Costs.** A palette plus one frozen icons dependency live in `:app`; the review screenshots are
artifacts of the gate and stay untracked until the owner keeps them.

## References

- `review/ui-before/` (v1), `review/ui-after/` (v2) — the acceptance evidence.
- ADR-005 §1 (what this supersedes), ADR-006 (the shell this UI surfaces in Diagnostics).
- Live emulator run in the v2 session: new thread → real turn → approval card → Allow → trust
  persisted → model retried → reply; screenshots 05–12.
