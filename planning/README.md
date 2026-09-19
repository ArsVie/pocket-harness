# planning/

Working notes, research, and decision records for PocketHarness — an Android coding harness with an OpenAI-compatible
model layer, a soft pastel light UI (ADR-007 superseded the v1 chrome), sessions presented as messaging threads, and a deliberately minimal
tool/harness surface in the manner of Pi and the DeepSeek harness's `minimal` preset.

## Provenance convention

Three kinds of statement appear in this folder, and they are labelled so they cannot be confused:

| Kind | Marker | Meaning |
|---|---|---|
| **Verified** | `(verified)`, or a file path / line number / URL | Read in source or on this machine by the agent that wrote it, or independently re-checked |
| **Source claim** | attributed to a paper section, file, or doc | What a reference says — not an endorsement |
| **Decision** | ADR in `decisions/` | Ours. May have no source. Recorded verbatim where it came from Ars |

Rule for this dossier: integrate across sources, never fabricate. Where the references are silent, the file says so.

## Research (`research/`)

| File | Contents |
|---|---|
| `00-synthesis.md` | **Start here.** Cross-source convergence, the load-bearing findings, and what does not transfer |
| `01-pi-harness.md` | Pi 0.84.1 — loop, system prompt, 8 tools (4 exposed by default), compaction, JSONL session tree, provider layer |
| `02-deepseek-harness-minimal.md` | The `minimal` agent preset verbatim from the local clone + live `~/.dsh` state — 1-sentence prompt, 2 tools, session format, DeepSeek wire contract |
| `03-arxiv-harness-engineering.md` | arXiv:2609.00006v1 — seven subsystems, 13 observations, 29 patterns, 18 recommendations, the scaffold, and applicability |

## Decisions (`decisions/`)

| ADR | Decision |
|---|---|
| `0001-execution-surface.md` | On-device, Termux-style execution — and the Android W^X constraint that comes with it |
| `0002-safety-floor.md` | Floor-level safety only, `rm` → trash, with the deferred items named explicitly |

## Status

Part 1 (research) is complete: three evidence-backed briefs, a synthesis, and two decision records. No application code
has been written yet; implementation gaps are listed in each ADR rather than guessed at here.
