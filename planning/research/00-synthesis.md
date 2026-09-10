# 00 — Research synthesis: what the three references agree on

Sources (all in this directory):
- `01-pi-harness.md` — Pi 0.84.1: shipped bundle, public source, pi.dev docs, arXiv §11.8/§14
- `02-deepseek-harness-minimal.md` — the `minimal` agent preset, verbatim, from the local clone + live `~/.dsh` state
- `03-arxiv-harness-engineering.md` — arXiv:2609.00006v1, 83pp, 11 harnesses

Independently re-verified by hand after the reports landed (not taken on the agents' word):
- `apps/cli/config/agent-presets/minimal/agent.cordis.yml` read in full — the 1-sentence prompt, `complete: true`,
  `includeRuntimeContext: false`, exactly two tools, no compaction rows. Confirmed.
- Pi `package.json:6` — "Coding agent CLI with read, bash, edit, write tools and session management", v0.84.1. Confirmed
  4 default tools; the 8 name literals in `dist/` confirmed.

---

## 1. The convergence table

Three independent sources, three different methods (shipped bundle, live config, paper corpus). They agree.

| | DeepSeek `minimal` | Pi (default) | Paper's MVH scaffold |
|---|---|---|---|
| **System prompt** | **one sentence** | ~2-sentence persona + deduped per-tool guidelines; 2,401 chars / 32 lines / ~600 tok | persona + tool list + tool guidance |
| **Tools** | **2** | **4 exposed** (8 built) | **4** |
| **Tool names** | `bash`, `str_replace_editor` | `read`, `bash`, `edit`, `write` | `bash`, `read_file`, `write_file`, `search_replace` |
| **Loop** | `while (await turn())`; no turn cap | ~790 lines; no planner, no reflection, no turn cap, no stuck detection — documented refusals | linear `while`; middleware only when ≥3 policies |
| **Compaction** | **absent, deliberately** | threshold at `contextWindow − 16384`, keep 20k, incremental summary merge | threshold + incremental summary |
| **Session** | header + ordered events, `seq` contiguous, `agentPreset` in header; zstd-framed JSONL | JSONL tree via `id`/`parentId`, `~/.pi/agent/sessions/--<path>--/<ts>_<uuid>.jsonl`, v3 | transcript as the durable artifact |
| **Extensions** | none in minimal | skills (agentskills.io) + ~33-event bus; **MCP rejected** | skills 9/11 > MCP 8/11 |
| **Policy** | none in prompt | delegated to `AGENTS.md`; no permission popups | policy-as-code, frozen never-allowed floor |

**The shape is unambiguous: 2–4 tools, a near-empty prompt, a linear loop, and the transcript as the durable object.**
Every source independently refuses the same things (planners, frameworks, code RAG, turn caps).

## 2. The two load-bearing findings

**(a) Minimalism is implemented as *suppression*, not omission.** The DeepSeek preset's whole 62-line file is a
`persona` row plus two tool groups. What makes it minimal is `complete: true` (this text is the *only* system-prompt
section — no identity, tool guidance, or listener may append) and `includeRuntimeContext: false` (zero runtime-context
snapshot messages). The preset then states: *"Context compaction is absent."* Meanwhile the host composition still
mounts ~25 tool rows; the web bundle **disables them by id rather than deleting them**, with the stated reason that an
absent row *"would silently reappear the day someone reorders the composition."*

→ Portable rule: a mode that owns the prompt must be able to **suppress** the default plane, and the suppression must
survive a reorder. On Android: skip the default tool set explicitly, keep the ids.

**(b) The prompt is not where the capability lives — the tool descriptions are.** The entire minimal system prompt is
`You are a helpful software engineer assistant.` All real instruction lives in tool descriptions: the `bash` tool ships
a 7-bullet description (the `str_replace_editor` schema carries ~7 field-level descriptions totalling more prose than
the prompt). Pi does the same with per-tool `promptSnippet` + `promptGuidelines`, deduplicated through a `Set`, so the
prompt *co-varies* with the active toolset.

→ Corollary the paper supplies: *"prompt rhetoric thins as trust calibrates."* A long prompt on a 2-tool harness is a
signal the tools are under-described.

## 3. Findings that are specific to our model

`deepseek-v4-flash` is DeepSeek-family, and report 02 extracted the exact wire contract from the adapter source:

- Request body adds two **top-level** fields: `thinking: {type: 'enabled'|'disabled'}` and
  `reasoning_effort: 'high'|'max'` (`llm-deepseek/src/serialize.ts:178-181`).
- Always `stream: true` with `stream_options: {include_usage: true}` (`:176-177`).
- `reasoning_content` deltas are persisted but replayed to the model **only on turns that carried tool calls**
  (`:96-99`) — a real token saving we can copy directly.
- `reasoningEffort` is an opaque adapter-owned level id validated *before any I/O*, failing
  `UNSUPPORTED_REASONING_EFFORT`. Treat the level→wire-spelling mapping as data, not code.

**Prompt-cache payoff is measured, not theoretical.** The live minimal session on this machine:
`uncachedInputTokens: 16127, cacheReadTokens: 138368, cacheWriteTokens: 0`. Roughly 90% of input tokens came from cache.
That is the dividend of a byte-stable prefix.

→ Rule: assemble persona + tool schemas **once per session**, never re-render per turn, keep the tool list
byte-identical across modes, append-only history, no reordering.

## 4. What the paper says that constrains us

- **Twin absences, ~4M lines / 12 trees / 11 systems:** 0 import a general-purpose agentic framework; 0 use
  vector-embedding RAG over code. Hand-rolled async loops and deterministic retrieval won. → No LangChain-equivalent,
  no embeddings, lexical search only.
- **Loop sophistication does not predict performance.** Mini-SWE-Agent implements all seven subsystems in ~100 lines
  and reports results in the same range as systems orders of magnitude larger. → Budget goes to UX, transport, safety —
  not loop cleverness.
- **Convergence became imitation over 90 days**, and behavioural policy migrated from prompt prose into configuration.
  → Put policy in a config/Kotlin object with a frozen floor, not in prompt sentences the model can be argued out of.
- **18 recommendations**, an 82-line reproducible scaffold, and (Rec 11/Pattern 2) a *never-allowed* set frozen at app
  start — Hermes's `--yolo` flag is frozen at import so injected text cannot flip the floor.

## 5. The execution gap — and how it closed

The single biggest delta between all three references and us was that **every one of them has a real shell on a real
filesystem**: `bash` is a PTY with 300s timeouts (DeepSeek minimal) or a subprocess (Pi); the paper's scaffold is
`bash` + three file tools. An Android app in the normal sandbox has no such shell, and the DeepSeek `bash` description
is written for a container (*"a mirror of common linux and python packages via apt and pip"*) — factually wrong on a phone.

**This gap is closed by decision, not by research** — see `../decisions/0001-execution-surface.md`. Execution is
on-device, Termux-style, so the reference tool surface transfers without a translation layer. Consequence to keep in
view: Android 10's W^X change removed execute permission for the app home directory, so the userland must either ship
in the APK's native lib directory (exec permitted from the read-only `/data/app/.../lib/` path) or the app must pin
`targetSdk` ≤ 28, which is how Termux itself survives. Recorded in the ADR with citations; not a blocker for a sideloaded PoC.

What still does not transfer, and is *not* resolved by that decision:

- The Cordis plugin/realm/scope container (a phone runs one session at a time — port the concept, not the DI container).
- Zstandard frame concatenation with per-batch checksums and torn-tail repair (plain JSONL + contiguous `seq` is enough).
- `powershell`; ripgrep/fd binary auto-download (fragile per-ABI on Android — fold search into the shell, as the
  DeepSeek preset already does by making `bash` the searcher).
- The ~33-event extension bus, subagents, ACP, hooks, MCP, plan mode, todo — all add tool schemas and prompt sections.
  The reference minimal preset excludes every one of them, so their absence here is fidelity, not omission.

Desktop-scale tuning also does not port: a 300s per-command timeout and a 16k-char clip budget assume a desktop context
window and desktop patience.

## 6. Decisions this research forced

Both are recorded as ADRs, because they are choices, not findings:

- `../decisions/0001-execution-surface.md` — on-device Termux-style execution (closes the shell gap above).
- `../decisions/0002-safety-floor.md` — floor-level safety with `rm` → trash, grounded in Obs 6 / §15.3 / Rec 11.

The distinction between a *source claim* and *our choice* is kept explicit in both. `rm` → trash appears nowhere in the
corpus; the paper's own scaffold omits sandboxing because Recommendations 9–10 are deployment-specific.

