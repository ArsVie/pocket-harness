# 03 — Harness Engineering (arXiv 2609.00006): engineering brief for PocketHarness

Distilled from the primary source. Every claim below is attributed to the paper by
section, table, or numbered observation/recommendation. Where the evidence is
anecdotal or rests on one or two systems, that is said so. This is not a book
report — it is the transferable engineering judgment, plus an applicability
section that is deliberately opinionated.

---

## 1. Citation and thesis

> Barbaste, P., Darrigol, T., Vu, G., & Wiltberger, T. (July 2026). *Harness
> Engineering: Anatomy, Architecture, and Evolution of Coding Agents — A
> Source-Code Study of Eleven Systems.* arXiv:2609.00006v1 [cs.SE], 15 Jul 2026.
> Wavestone AI Lab / Inclusive Brains. 83 pp, 7 figures, 18 tables, CC BY 4.0.
> Read at https://arxiv.org/html/2609.00006v1 (full text also cached locally at
> `~/.hermes/profiles/dev/cache/web/arxiv.org-6c073d0504.md`).

**Thesis, in the paper's own words** (abstract and §14):

> "In the first half of 2026 the coding harness completed a turn from tool to
> *platform*. Harnesses became importable SDKs while framework vendors shipped
> harnesses; marketplaces, switching-cost tooling, and enterprise governance
> layers appeared; the agent became addressable as a model behind an
> OpenAI-compatible endpoint; and a meta-harness now orchestrates eleven vendor
> harnesses … behind one API."

And the one-line algebra it builds on (§2.1, quoting Trivedy/LangChain):

> "An agent is a model plus a harness. The harness is everything except the
> model: the runtime that couples an LLM to the world—its loop, its tools, its
> context, its safety controls, its orchestration, and its extension surfaces.
> Harness engineering is the discipline of designing and evolving that runtime."

Method: source-code anatomy, not benchmarking. Eleven systems pinned to July
2026 (Claude Code, Codex CLI, Gemini CLI, Mistral Vibe, OpenHands, Aider,
Mini-SWE-Agent, Hermes, Pi, OpenCode, OpenClaw) plus Omnigent as a *meta-harness*
contrast point; eight of the eleven were re-pinned from an April-2026 edition,
giving a controlled one-quarter longitudinal diff (§4.1, §14.5). The authors
state plainly: "We claim to know how they are *structured*," not how fast they
run; three observations from April required substantive revision (§15.6).

---

## 2. What a harness is, and the seven canonical subsystems

§2.2 draws four boundary lines the paper insists on:

- **A harness is not a scaffold** — "scaffold" is the structural code (loop,
  registries); "harness" is the shipped runtime that embeds it.
- **A harness is not an agentic framework** — a framework is a library you
  *import to build* an agent; a harness is a runtime you *work inside*.
- **A harness is not an evaluation harness** — opposite direction of wrapping.
- **A harness is not an orchestrator** — a meta-harness coordinates harnesses
  from above and implements no editing loop of its own.

§2.3 asserts every system, "from the 100-line research baseline to the
million-line production CLI," must take a position on the same seven subsystems
"even when that position is deliberate absence." Table 1 is the component map:

| Subsystem | Role | Minimal form observed | Maximal form observed | § |
|---|---|---|---|---|
| Agent loop | Alternates inference with action execution; owns stop conditions | **Mini-SWE-Agent**: linear `while` over one bash tool | **OpenHands**: event-sourced conversation over a persistent event log, parallel action batches | 6 |
| LLM integration | Provider protocols; prompt assembly; caching, thinking, routing | **Mini-SWE-Agent**: one LiteLLM call, one Jinja template | **Hermes**: five owned transports, 29 provider profiles; **Codex**: server-delivered model catalog | 7 |
| Tools & actions | Defines/executes what the agent can do, file editing above all | **Mini-SWE-Agent**: bash only | **Claude Code**: 43 typed tools with deferred loading; **Codex**: tool calls as V8-executed code | 8 |
| Memory & context | Rations the context window; persists across turns/sessions | **Mini-SWE-Agent**: unbounded linear history | **Codex**: agent-maintained cross-session memory pipeline; **Gemini CLI**: graph-based context distillation | 9 |
| Safety & permissions | Decides what runs, what asks, what is forbidden; isolates execution | **Mini-SWE-Agent**: cost and step limits | **Codex**: policy rules + LLM approval reviewer + three-platform OS sandbox | 10 |
| Orchestration | Spawns and coordinates sub-agents | **Aider**: none (single-agent by design) | **Claude Code**: recursive composition; **Omnigent**: cross-vendor coordination (meta-layer) | 11 |
| Extensibility | Config, hooks, skills, plugins, MCP | **Mini-SWE-Agent**: structural typing (Python `Protocol`) | **Pi**: everything-is-an-extension runtime; **Codex**: marketplace-distributed plugins | 12 |

Two cross-cutting surfaces sit alongside the seven: the **interface layer**
(TUI / CLI flags / IDE protocol / HTTP server / SDK) and the **session substrate**
(transcripts, persistence, resume/fork) — both shared by several subsystems and
both carrying the platformization argument.

**The floor is real.** Mini-SWE-Agent implements all seven in ~100 lines and
reports SWE-bench Verified results "in the same range" as systems three orders of
magnitude larger — a self-reported figure the paper repeatedly caveats (different
models/dates; §2.4, footnote 4). The point survives the caveats: "the floor is
low." Its §6.2 Listing 1 is reproduced verbatim in the HTML: `run()` seeds
system+user messages then `while True: step()`; `step()` is `query()` then
`execute_actions()`; `query()` checks limits and appends the model reply;
`execute_actions()` runs each action through `subprocess.run()` and appends the
observation. No state machine, no event sourcing, no concurrency.

---

## 3. The 13 cross-cutting observations

The paper numbers observations 1–13. All are listed; evidence is the paper's.

**Obs 1 (§5)** — Three orders of magnitude in code size, yet loop sophistication
does not predict benchmark performance; most production mass addresses concerns
*orthogonal* to task completion. *Evidence*: Mini-SWE-Agent's linear loop vs
OpenHands's event-sourced engine; roughly three-fifths of OpenCode's non-test
source is its TUI/web/desktop/SDK clients; Codex's July tree devotes six-figure
line counts to app-server transports, plugins, and a realtime voice layer "that
no benchmark will ever measure."

**Obs 2 (§7.1)** — Provider-native optimizations (cache boundaries, extended
thinking, reasoning effort, model-specific prompts) are not gated on tight
coupling; they are gated on *who pays the per-provider conditional-code cost*.
*Evidence*: Claude Code's Blake2b-hashed static cache boundary; Codex's
`thread_id`-derived `prompt_cache_key`; Gemini CLI's `ModelRouterService`; but
also Hermes's five hand-rolled transports with bit-perfect prefix normalization;
Pi's `cache_control` breakpoints with TTL tiers plus a per-turn cache-miss
*dollar-waste audit*; OpenCode emitting six providers' cache dialects at once and
capping its system prompt at two messages to match cache slots. LiteLLM-based
systems (OpenHands, Aider, Mini-SWE-Agent) exercise the menu only partially.

**Obs 3 (§7.3)** — Prompt rhetoric converges where engineering experience
converges, then *thins* as trust calibrates. *Evidence*: anti-gold-plating
language appears in near-isomorphic phrasings across six independently developed
scaffolds; the no-autonomous-commit rule was universal in April, then split three
ways by July (Mistral Vibe *reversed* it into commit instruction with a mandated
co-author trailer; Codex's newest prompts drop both it and the anti-gold-plating
directives); three rhetorical schools now coexist (harness-enforced prose,
structural precedence contracts, total delegation to user context); model-conditional
rhetoric is its own axis. Stable invariant across 11/11: **no policy-level refusal
language anywhere** — alignment work is never duplicated in the system prompt.

**Obs 4 (§8.4)** — File-editing strategy is a top determinant of modification
accuracy, and model-aware polymorphism is no longer Aider's alone. *Evidence*:
Aider selects edit *format* per model via a prompt factory; OpenCode swaps the
*toolset* per model (GPT-family models get a Codex-style patch DSL and lose the
string-replacement tools); editing machinery now shows explicit cross-harness
lineage (Hermes's matcher is annotated "inspired by OpenCode"; OpenCode's cascade
credits Cline and Gemini CLI); Mistral Vibe *migrated off* fuzzy SEARCH/REPLACE to
Claude Code-style exact matching within one quarter; Gemini CLI added an LLM
"edit fixer" subcall.

**Obs 5 (§9.6)** — Persistent memory has replaced compaction as the frontier of
context engineering; what differentiates the field is the memory *write path*.
*Evidence*: seven of eleven systems use threshold-triggered LLM compaction (a de
facto standard); four governance models coexist — agent-maintained (Codex),
human-gated (Gemini CLI's patch inbox), model-direct-but-bounded (Hermes's
character-capped Markdown), pre-turn agentic recall (OpenClaw's Active Memory);
and **none of the eleven uses embedding-based retrieval as its primary memory
substrate**, with Hermes's SQLite FTS5 the production ceiling.

**Obs 6 (§10.2)** — OS-level sandboxing is among the most code-expensive
capabilities in the corpus, and it is a *choice*, not a consequence of scale.
*Evidence*: April's "biggest systems are the sandboxers" correlation is falsified
— Hermes (~642K lines) ships zero OS-level isolation primitives, delegating to
six pluggable execution backends and spending its safety budget on *content-borne*
threats; OpenCode (~578K lines) is policy-only. Pi documents the absence as a
security argument. Where built (Codex, Gemini CLI), it is a substantial
multi-thousand-line investment — and the meta-harness re-pays the bill one layer up.

**Obs 7 (§11.1/§11.7)** — Coordinator-worker delegation emerged independently
across the corpus. *Evidence*: it appears in all four provider-native systems, in
OpenHands (parallel delegation over conversation trees), Hermes
(configuration-gated orchestrator role plus a subprocess swarm over a SQLite
blackboard), OpenCode (concurrent child sessions), and OpenClaw at the protocol
layer. But the implementations diverge on what they optimize (cost vs isolation/
scale vs portability vs simplicity vs containment) — and Pi deliberately ships
single-agent, relegating sub-agents to extension space.

**Obs 8 (§12.5)** — Skills have overtaken MCP as the corpus's most-adopted
extensibility standard: **9/11 systems implement SKILL.md bundles versus 8/11 for
MCP**. *Evidence*: the April tie (6/8) broke when Pi implemented agentskills.io
while rejecting MCP outright. Three second-order developments: deferred loading
is now near-universal (8 of 9 adopters); conditional activation (Claude Code
`paths`, OpenHands PathTrigger, OpenClaw `requires`) pushes JIT context
engineering into the extensibility layer; and a supply chain emerged (hosted
registries in four systems, trust tiers and quarantine in Hermes, provenance in
OpenClaw) together with the **first agent-authored skills** (Hermes's
self-improving loop, Gemini CLI's extraction inbox).

**Obs 9 (§13.2)** — The twin absences. See §5 below for the full treatment.

**Obs 10 (§13.2)** — Anthropic's *Effective Agents* series (Dec 2024–Sept 2025)
lines up closely with the architectures observed across the four provider-native
systems, which were built independently. *Evidence*: all seven prescriptions map
onto corpus behaviour. The authors explicitly refuse causal weight: "Whether this
reflects shared empirical reality, public-guidance influence, or both is an open
question."

**Obs 11 (§13.3)** — Inter-agent protocol placement evolved from a two-role story
to a **three-role** story. *Evidence*: ACP ships in six of eleven systems and now
serves (1) its designed editor↔agent boundary, (2) *harness hosting* — a role
outside its design brief — and (3) via A2A, still only in Gemini CLI, the
cross-vendor mesh. For a harness's *own* sub-agents, eight of nine multi-agent
systems still use in-process primitives.

**Obs 12 (§14.5)** — The coding-agent harness completed its platform turn in
H1 2026, and every layer is visible in source. *Evidence*: extension substrates
converged; capability distribution acquired marketplaces, registries, trust tiers
and agent authors; vendors shipped importers for each other's on-disk state and
MDM-grade governance; harnesses became importable SDKs while framework vendors
shipped harnesses; the agent became addressable as a model behind an
OpenAI-compatible endpoint; and a meta-harness orchestrates eleven vendor
harnesses. "The competitive unit of the field is no longer the agent loop; it is
the ecosystem surface around it."

**Obs 13 (§16.10)** — The 90-line minimum viable harness implements **10 of the
18 recommendations directly** and is compatible with the remaining eight, with no
framework dependencies, no RAG, no vector store, no multi-agent orchestration,
and no sandbox. The authors *conjecture without proof* that it would match
Mini-SWE-Agent's SWE-bench numbers on a frontier model, and that moving beyond
those numbers is "primarily a model-capability question … rather than a scaffold
question."

---

## 4. The 29 recurring design patterns

§13.1 Tables 11–12: seventeen from April (membership updated) plus twelve new.
The third column is PocketHarness relevance — **A**dopt, **L**ater, **O**ut of
scope / wrong for us.

**April seventeen (Table 11):**

| # | Pattern | One-line description | PH |
|---|---|---|---|
| 1 | Event Sourcing | Actions/observations appended to a persistent event log (OpenHands, Pi, OpenCode) | L |
| 2 | Policy-as-Code | Safety rules as executable configuration (Codex Starlark, Claude Code hooks, Gemini TOML, Hermes config+hardline floor) | **A** |
| 3 | Recursive Composition | Agents spawn sub-agents with forked/linked context | O |
| 4 | Polymorphic Edits | Model-aware edit format/toolset selection (Aider, OpenCode) | L |
| 5 | Deferred Loading | Tools/skills hidden from prompt, discovered on demand (Claude Code, Codex BM25, Hermes bridge tools) | L (we are under the ~15 threshold) |
| 6 | Template Method | Base class defines flow; subclasses override parse/format | O |
| 7 | Protocol Interfaces | Structural subtyping seams for pluggable components (Mini-SWE-Agent, Pi Operations) | **A** |
| 8 | LLM Summarization | LLM compresses conversation history (9 systems) | **A** |
| 9 | Stuck Detection | Automated detection of repetitive behaviour | **A** (cheap caps only) |
| 10 | Reflection Loop | Inner self-correction with lint/test feedback (Aider; cousins in Gemini/OpenCode) | O (needs an execution loop we don't have yet) |
| 11 | Prompt Caching | Cache-boundary prompt structure or session-key reuse | **A** (stable prefix, volatile tail) |
| 12 | Context Forking | Clone parent state for sub-agent isolation | O |
| 13 | Middleware Pipeline | Composable turn-level policies orthogonal to the loop body (Mistral Vibe) | **A** (light form) |
| 14 | JIT Repo Context | Hierarchical Markdown context files auto-discovered + on-demand injection (8 systems) | **A** (adapted to device storage) |
| 15 | Skills (capability bundles) | SKILL.md directories with YAML frontmatter (9 systems) | L |
| 16 | Conditional Activation | Skills/tools gated by environment or file paths | O |
| 17 | Turn-Level Checkpoint | Filesystem snapshots with rewind and file restoration | O |

**July twelve (Table 12):**

| # | Pattern | One-line description | PH |
|---|---|---|---|
| 18 | Agent-Maintained Memory | Background sub-agent extracts/consolidates cross-session memory (Codex; human-gated in Gemini CLI) | O |
| 19 | Outer Verification Loop | Scaffold-level judge/guard validating completion outside the turn loop (OpenHands `/goal`, Hermes verify-on-stop) | L (cheap shadow version worth it) |
| 20 | Self-Improving Skill Loop | Agent authors/patches/curates its own capability bundles (Hermes) | O |
| 21 | Lineage Compaction | Compaction as session rotation with a searchable ancestry chain (Hermes) | O |
| 22 | Session-Tree Version Control | Append-only entry tree with movable head; fork/rewind/branch summaries (Pi, OpenHands) | L |
| 23 | Minimal-Core / Extension-Host | Safety, sandbox, sub-agents, plan mode relocated to a runtime event bus (Pi) | L (philosophy yes, event bus no) |
| 24 | Client/Server Harness | Embedded API server; every UI is a client (OpenCode, OpenHands) | **A** — but inverted: *we* are the client |
| 25 | Model-Family Prompt Matrix | Distinct base prompts dispatched per model family/generation (Codex, OpenCode, Hermes) | L (we target one model) |
| 26 | Cache-Dialect Fanout | All providers' cache-control dialects emitted simultaneously (OpenCode) | O (OpenAI-compatible only) |
| 27 | Syntax-Aware Command Permissioning | Commands parsed (tree-sitter) and grants scoped by argument arity (OpenCode, Mistral Vibe, Hermes) | O |
| 28 | Untrusted-Content Delimiting | Tool/web results wrapped in taint markers with delimiter defanging (Hermes, OpenHands) | **A** (any web fetch or pasted content) |
| 29 | Harness Mimicry | Client presents a first-party harness's identity to ride its subscription backend (Pi) | O (and questionable) |

**Relevance summary:** the patterns that transfer to a single-agent, mobile,
minimal harness are the *structural* ones — policy-as-code, protocol interfaces,
summarizing compaction, cheap stuck detection, prompt caching, a light middleware
pipeline, JIT context files, untrusted-content delimiting, and doing the
client/server split with us as the client. The patterns that belong to large
multi-agent/IDE-integrated systems — recursive composition, context forking,
session-tree version control, agent-maintained memory, self-improving skill
loops, model-family prompt matrices, cache-dialect fanout, syntax-aware
command permissioning, harness mimicry — are out of scope. This is exactly the
paper's own inventory-vs-structural distinction (§14.5): structural claims are
durable, inventory claims decay in weeks.

---

## 5. The two headline negative results (and the extensibility/protocol counts)

**Absence 1 — no general-purpose agentic framework in any agent runtime.**
*Numbers*: **0 of 11** systems (and 0 of 12 trees including the meta-harness).
Every dependency manifest was inspected and every source tree grepped for
LangChain, LangGraph, LlamaIndex, AutoGen, CrewAI, Pydantic AI, Genkit, Haystack
agents, Semantic Kernel, Google ADK, Smolagents, Swarm, and Agno — across
**roughly 4 million lines of Python, TypeScript, and Rust**. Two boundary cases
are named for precision: Aider ships one optional extra that installs
`llama-index` to run doc-RAG over *Aider's own documentation* (not loop
orchestration), and OpenCode delegates its inner LLM/tool plumbing to Vercel's AI
SDK (a provider-abstraction layer, not an orchestration framework, and an
in-house replacement sits behind a flag). Sharp datums: **Gemini CLI uses neither
of Google's own frameworks (Genkit, ADK)**, and Hermes's only "LangChain" strings
sit inside bundled skill *documentation*. Every loop is hand-rolled in the host
language's native primitives (asyncio, blocking Python, Promise/async-iterator,
Tokio).

**Absence 2 — no vector-embedding RAG over code.**
*Numbers*: **0 of 11** systems use embedding-based retrieval over the source
tree. The search covered vector-store dependencies (Chroma, Pinecone, Weaviate,
Qdrant, Milvus, FAISS, LanceDB, sqlite-vec, Elasticsearch in vector mode) and
embedding libraries, and folders/files matching embedding, vector_store,
vectordb, rag, retrieval. Instead: ripgrep, tree-sitter, glob, file-system
traversal, and auto-discovered Markdown context files (§13.2 Table 13). One
shift since April: OpenClaw's default memory plugin now runs hybrid sqlite-vec
KNN + FTS5/BM25 with embeddings on by default — **for conversation memory only,
never for reading the source tree**. Hermes is the opposite choice at scale:
deliberately lexical SQLite FTS5 (BM25 + trigram for CJK), "no LLM calls
anywhere," embeddings confined to opt-in plugins.

**The authors' interpretation.** Both absences "survived a threefold corpus
expansion and a three-month re-audit" (8 → 11 systems + meta-harness; two
manifest/import sweeps). They expected to find frameworks in the open-source
projects and ran the counterexample search for weeks before accepting it. Their
reading: production harnesses "operate on a different complexity budget from
generic LLM applications" — debuggability and prompt transparency outweigh
framework reuse once the failure mode is mutating real code (silent prompt
corruption, opaque caching, version-incompatible tool schemas). For RAG, the
reasons are domain-specific: code carries dense deterministic structural metadata
that semantic similarity cannot replicate; code changes minute to minute so
pre-indexed embeddings are stale by construction; every coding environment
already ships near-optimal retrieval (ripgrep/find/glob); and CodeRAG-Bench's
gains are "highly variable across tasks." §13.2 also notes these two absences
get a *historical resolution*: the harness–framework merger (§14.2) — harnesses
became importable SDKs, framework vendors shipped harnesses (LangChain's Deep
Agents on LangGraph, Pydantic AI Harness, Strands harness-sdk), and the meta-harness's
baseline dependencies are `claude-agent-sdk` and `openai-agents`. §15.6 flags the
finding as "strong but structurally conservative": they did not trace internal
forks, dynamic imports, or transpiled distributions.

**Skills vs MCP, exactly.** Skills **9/11** (Aider and Mini-SWE-Agent abstain);
MCP **8/11** (7 of 10 coding-first). April was tied 6/8. The tie broke because
Pi implements agentskills.io while *rejecting MCP outright* — "build CLI tools
with READMEs." The `.agents/skills/` canonical path is accepted by six systems,
and OpenCode *deliberately searches a competitor's home* (`~/.claude/skills`).
MCP is described as "a wire protocol for an agent to talk to a separately running
process"; skills as "a file-system convention for packaging an
instruction-plus-script bundle the agent reads directly."

**ACP's third role — harness hosting.** ACP ships as a first-class production
dependency or implementation in **six of eleven** systems (Mistral Vibe, OpenClaw,
OpenCode, Hermes, OpenHands, Gemini CLI-through-A2A). It now serves three roles:
(1) the designed editor↔agent boundary (the "LSP role for ACP"); (2) **harness
hosting** — "the role nobody held in April" — where OpenHands's `ACPAgent`
delegates its `step()` to an external ACP server with provider metadata for
pinned `claude-agent-acp`, `codex-acp`, and `gemini --acp` binaries, so "rival
harnesses become interchangeable brains inside an OpenHands conversation," and
Hermes consumes an ACP agent as a *model transport* (GitHub Copilot's CLI as a
chat backend); (3) via A2A, still Gemini CLI's alone, the cross-vendor mesh. The
practitioner guidance the paper draws (§13.3): "build an ACP *server* … keep your
sub-agents in-process; and treat A2A as a bet on a cross-vendor mesh whose
at-scale demand remains unproven."

---

## 6. Longitudinal findings: ninety days of harness evolution

§14.5 diffs the same eight harnesses across one quarter. Four movements:

1. **Convergence became imitation.** April's convergences were mostly
   independent rediscovery; July's are *traceable*. Codex adopted Claude Code's
   hook event vocabulary **verbatim** and its plan-mode ergonomics; OpenHands
   adopted Claude Code's plugin manifest format, its task-tool signature, and its
   static/dynamic cache boundary; OpenCode reads Claude Code's skills directory;
   Hermes's source comments credit OpenCode (edit matcher), Codex (smart
   approvals), OpenClaw (orchestrator prompt), and Goose (context hints).
   "Cross-harness lineage is now written in the code itself." Codex also ships a
   first-class **importer for Claude Code's on-disk state** (session JSONL →
   Codex rollout items; `settings.json` → `config.toml`).
2. **Patterns diffused down the corpus.** Deferred tool loading went from one
   system to three (plus seven skills variants); read-only plan modes from two to
   **all four** provider-native systems; LLM approval classifiers from one to two;
   turn-level checkpointing from one to three; safety-aware scheduler
   partitioning from one to two. "The half-life of a competitive distinctive in
   this field is currently measurable in weeks."
3. **Policy migrated out of prose.** Codex's newest model prompts dropped the
   no-commit and anti-gold-plating rules in favour of feature flags; Mistral Vibe
   deleted its "Never Commit" hard rule and re-founded its prompt on a seven-level
   precedence contract; OpenHands teaches commit mechanics. "Behavioral policy is
   moving from the prompt (where the model reads it) to configuration (where the
   platform enforces it)."
4. **The trees moved at platform speed.** Codex's workspace nearly doubled
   (621K → ~1.12M lines of Rust, 89 → 126 crates) in one quarter; Mistral Vibe
   grew 77% (35.6K → 63K lines); Aider settled into community maintenance with 18
   commits in the window.

**Why it matters for anyone writing a new harness now.** The paper's own
methodological lesson: "in this field, *inventory* claims (tool counts, feature
cells, version pins) decay in weeks, while *structural* claims (loop taxonomy,
subsystem anatomy, the absences) have so far proven durable." Read any single
cell of any table as perishable. Copy structure, not feature lists. And note the
direction of travel: policy that lives in prompt prose erodes as models
internalize norms — put your non-negotiables in configuration the platform
enforces, not in prose a model may drift from.

---

## 7. What the paper says about Pi and Hermes

These are our two reference harnesses; the paper treats them as opposite poles.

### Pi — the minimal-core countertrend (earendil-works / M. Zechner, TypeScript, v0.80.6)

- **Signature (Table 4):** "Everything-is-an-extension core; session-tree version
  control." Medium scale, 35 providers, **7 tools built / 4 exposed by default**,
  no sandbox (by design).
- **Loop (§6.2):** "the purest iterative implementation in the corpus after
  Mini-SWE-Agent" — a **~790-line functional core** with no planner, no
  reflection, no turn cap, no stuck detection, no cost kill-switch; every absence
  is a **documented design refusal**. `steer()` injects mid-run user input after
  the current turn's tool calls; `followUp()` drains only when the agent would
  otherwise stop. Tool calls run in parallel (`Promise.all`) with a realpath-keyed
  per-file mutation queue, plus a **truncation-poisoning guard**: a message cut at
  the length limit has *all* its tool calls failed unexecuted, because
  salvage-parsed arguments can validate while incomplete.
- **Prompt (§7.2–7.3):** a **~170-line builder emitting 30–40 lines** — persona,
  tool list, deduplicated per-tool snippets (`promptSnippet`/`promptGuidelines`,
  so the prompt *co-varies with the active toolset*), `<project_context>` from
  AGENTS.md, `<available_skills>` XML index, date, cwd. Verbosity is one bullet
  ("Be concise"); anti-gold-plating and commit policy are **silent, delegated** to
  user context files.
- **Editing (§8.4):** multi-edit exact replacement with **no similarity threshold
  at all**, only Unicode/whitespace canonicalization (NFKC, smart quotes),
  computed in normalized space and overlaid line-wise so untouched lines keep
  exact bytes; async diff preview.
- **Safety (§10.8):** documents the *absence* of safety infrastructure as a
  principle — a partial in-process sandbox "would be easy to misunderstand as a
  security boundary." One built-in gate (project trust); the reference
  permission-gate is ~80 lines of user space; the append-only session tree *is*
  the audit trail.
- **Context (§9.5):** compaction at `contextWindow − 16,384` keeping 20K recent,
  *iteratively merged* summaries, a `session_before_compact` veto hook, over an
  append-only JSONL **tree** (`/tree`, `/fork`, `/clone` unify checkpointing,
  rewind, branch exploration). Filesystem state is not restored.
- **Extensibility (§12.1/12.4/12.5):** the core *is* an event bus (~33 typed
  events, 78 example extensions via a package manager); the corpus's **one
  principled MCP holdout** ("build CLI tools with READMEs"); skills loaded via the
  ordinary read tool; sub-agents are a ~1,000-line *example extension*. Publishes
  no benchmark number — it solicits real-world session data "instead of toy
  benchmarks."

**Engineering read:** Pi is our closest philosophical relative — its prompt
discipline and its refusal to build machinery it cannot honestly call a boundary
are directly transferable; its event-bus substrate is not (it is the thing that
makes Pi *large*).

### Hermes — the self-improving personal/SWE hybrid (Nous Research, Python, 0.18.2)

- **Signature (Table 4):** "Self-improving skill loop; lineage compaction;
  verify-on-stop." Very large, 69 tools, multi-agent, six pluggable execution
  backends, Web + 28 channels. Flagged as a *hybrid* — a multi-channel assistant
  gateway that also ships a full native coding toolset (§4.1).
- **Loop (§6.2):** one tool-calling loop per user turn under a thread-safe
  `IterationBudget` (default 90, with a final grace call), eleven enumerated
  turn-exit reasons, and two stop-guards. The **verify-on-stop guard** "rewrites a
  text-only response into a continuation whenever the turn mutated code files
  without producing fresh verification evidence — the reflection loop's function,
  relocated … to its stop condition, at a fraction of the cost." Stuck detection
  hashes tool name + sorted-JSON args (SHA-256), warns after 2, halts after 8 —
  **but the hard stop ships disabled**. Tool batches parallelize on eight workers
  only when read-only-safe or path-scoped; mid-turn steering is spliced into the
  last tool result behind an anti-injection marker.
- **LLM integration (§7.1):** five owned transports behind **29 declarative
  ProviderProfile plugins** — the profile "is read by the transport instead of
  receiving 20+ boolean flags" — with metadata for 3,800+ models from models.dev.
- **Prompt (§7.2–7.3):** three-tier assembly (stable/context/volatile) built
  **once per session**; ~15 guidance blocks gated by model family — tool-use
  enforcement goes to GPT/Codex/Gemini/Grok/Qwen/DeepSeek and never to Claude;
  `system_and_3` caching with four `cache_control` breakpoints; **bit-perfect
  prefix normalization** (sorted tool-call JSON, date-only timestamp, frozen
  memory snapshot) so even local KV caches hit. Anti-fabrication is *mechanized*
  by verify-on-stop, not just stated.
- **Context (§9.5):** **lineage compaction** — trigger at 50% of context minus
  max-tokens (64K floor), protect first three messages plus a budgeted tail,
  iterative summary capped at `min(5% context, 12K)`. The unique construct:
  **compaction does not rewrite the transcript — it ends the session**
  (`end_reason='compression'`), rotating to a child chained by
  `parent_session_id`, so no history is destroyed.
- **Memory (§9.6):** two bounded Markdown files (MEMORY.md 2,200 chars; USER.md
  1,375) injected as a **frozen snapshot** so mid-session writes don't invalidate
  the cache; recall is deterministic `session_search` over SQLite FTS5 (BM25 +
  trigram), "no LLM calls anywhere."
- **Safety (§10.7):** zero OS-level isolation; a 3,200-line approval module whose
  docs state "config.yaml IS the security policy." User deny-globs; a
  **twelve-pattern hardline floor** (`rm -rf /`, mkfs, dd to block devices, fork
  bombs) that **survives `--yolo`** because the YOLO env var is frozen at module
  import; 47 dangerous-command patterns on *deobfuscated* variants; an external
  cosign-verified Rust scanner; an optional auxiliary-LLM gate (`_smart_approve`:
  temperature 0, 16 max tokens, APPROVE/DENY/ESCALATE). Content-borne defense:
  promptware scanning of context files, memory writes, MCP descriptions and skill
  installs (trust tiers + quarantine); SSRF guards; untrusted results wrapped in
  `<untrusted_tool_result>`. Flagged absences: no per-tool allow/ask/deny matrix,
  **no append-only audit log**.
- **Skills (§12.5) / Orchestration (§11.7):** 72 bundled skills seeded into
  `~/.hermes/skills/`, a trust-tiered Skills Hub with pre-install scanning,
  three-tier progressive disclosure, self-authoring via `skill_manage`. Delegation
  is flat by default (in-process thread pool, toolsets intersected with the parent,
  six-tool blocklist, depth 1); an orchestrator role unlocks nesting, a Kanban
  swarm runs subprocesses over a SQLite blackboard, `/moa` fans out up to eight
  advisory models.

**Engineering read:** Hermes is the maximal implementation of the *values* we
hold — policy-as-code with a floor that survives YOLO, deterministic lexical
recall, cache-hygienic bounded memory, verify-on-stop, cheap warn-first stuck
caps. Copy its *judgments*, not its *volume*.

---

## 8. The 18 design recommendations (§16)

Reproduced as the paper numbers them, with the rationale condensed from the
paper's own evidence lines.

1. **Start with a linear while loop; graduate to a middleware pipeline only when
   orthogonal turn policies emerge.** Loop sophistication doesn't predict
   performance (Obs 1); Mini-SWE-Agent's 50-line loop reports 74%+. Upgrade when
   you need ≥3 independent turn policies.
2. **If you ship a foundation model, couple tightly to your home provider and
   expose a generic fallback; if you don't, budget for per-model metadata.**
   Provider-native optimizations are reachable from a multi-provider substrate —
   the cost is a maintained per-provider conditioning layer, paid once centrally
   (Obs 2).
3. **Begin with just a bash tool. Add more tools only in response to observed
   failure modes.** Mini-SWE-Agent's single-tool design reports 74%+; "more tools
   don't always lead to better outcomes." Common sequence: add read/write when
   truncation bites; grep/glob when bash+rg feels awkward; search_replace when
   full-file writes waste tokens.
4. **When your tool count exceeds ~15, adopt deferred tool loading.** Claude
   Code's `shouldDefer` + ToolSearchTool cuts the initial prompt ~40%; Codex
   converged independently with BM25-ranked `tool_search`; Hermes collapses
   overflowing catalogs into three BM25-searched bridge tools once schemas would
   exceed 10% of context. Below ~15 the indirection isn't worth it.
5. **Match your edit-tool contract to your model tier: exact unique-substring
   replacement for frontier models, a fuzzy cascade for open/weaker models — and
   in either case handle drift at the tool, never by line numbers** (Obs 4).
   Gemini's LLM edit-fixer is the emerging third option.
6. **Auto-discover hierarchical Markdown context files at project, user, and
   extension scopes — and read your neighbors' filenames too.** Inject top-level
   content near the top of the system prompt; surface nested files just-in-time;
   let the model persist durable facts by edits, bounded snapshots, or a
   human-reviewed inbox (Obs 5).
7. **Implement threshold compaction at a fixed buffer below the context window;
   preserve a verbatim recent tail; merge summaries incrementally; wire the same
   routine to fire reactively on overflow.** "Aggressive compaction protects
   against context rot; conservative preservation keeps recent reasoning coherent."
8. **Do not build RAG over code. Use ripgrep, glob, tree-sitter, and file-system
   traversal instead** (Obs 9; 0/11).
9. **For a developer-tool (semi-trusted) context: a three-mode approval system
   (PLAN / DEFAULT / YOLO) with permission-scope patterns.**
10. **For enterprise/shared/automated contexts: OS-level sandboxing with
    policy-as-code and per-agent audit trails.** Reuse OS binaries (as Gemini CLI
    does) rather than writing namespace plumbing.
11. **Regardless of tier, codify safety rules as data or dedicated policy files
    rather than imperative code — and if you support a YOLO mode, keep a floor
    beneath it.** Hermes's twelve hardline patterns survive `--yolo`, with the
    bypass flag frozen at module import.
12. **Stay single-agent until you can point to a concrete breadth-first
    exploration phase where parallel context isolation clearly beats serial
    search.** Multi-agent systems use ~15× more tokens than a chat baseline (not
    vs a single-agent pipeline) and "most coding tasks involve fewer truly
    parallelizable tasks than research."
13. **Ship an ACP *server* — it now buys editors, hosts, and meta-orchestrators at
    once. Keep your own sub-agents in-process.** Do not adopt ACP/A2A for
    main↔sub-agent coordination; A2A remains "defensible, unproven" (Obs 11).
14. **Use Skills for capability templates (workflows, domain knowledge, procedural
    recipes) and MCP for external integrations (Slack, databases, internal APIs)
    — in that order of priority** (Obs 8). Treat third-party skills as packages:
    trust tiers, scanning, quarantine.
15. **Do not use LangChain, LangGraph, AutoGen, CrewAI, LlamaIndex, Pydantic AI,
    Genkit, Google ADK, or Semantic Kernel for the agent runtime.** 0/11 do; use
    raw SDK calls. Corollary: if you want batteries included, the *harness SDKs*
    are the framework layer now.
16. **Do not build a vector-embedding retrieval layer for code.** 0/11 do. If you
    believe your domain needs it, first prove it beats ripgrep+tree-sitter on a
    held-out task set.
17. **Do not wrap every upstream SaaS API as a 1-to-1 tool.** "A common error
    we've observed is tools that merely wrap existing software functionality or
    API endpoints." Consolidate.
18. **Do not over-engineer stuck detection — but do ship the cheap caps, which
    cost a dozen lines.** Claude Code and Codex ship none and are fine; the floor
    moved (Mini-SWE-Agent caps malformed responses and wall-clock; OpenCode's
    doom-loop check is a three-identical-calls counter routed to a permission
    ask; Hermes hashes signatures with the hard stop disabled).

---

## 9. The 90-line minimum-viable-harness scaffold (§16.10, Listing 3)

The paper's own scaffold: "a minimum viable harness in ~90 lines of Python,
combining the patterns recommended above: linear loop (Mini-SWE-Agent),
middleware-style policies (Mistral Vibe), four-tool surface (bash, read, write,
search_replace), hierarchical Markdown context auto-discovery (all four
provider-native systems), and threshold compaction (Claude Code/Gemini
CLI/Mistral Vibe). It is not a drop-in library; it is a scaffold to be copied and
specialized." **Recovered from the paper's embedded Listing 3 data URI; 82
physical lines as extracted (the paper counts ~90 LoC). Reproduced faithfully:**

```python
from __future__ import annotations
import asyncio, json, pathlib, subprocess
from dataclasses import dataclass, field
from typing import Any, Protocol

# ---- Provider abstraction (Recommendation 2): provider-first fallback ---------
class Model(Protocol):
    async def complete(self, messages: list[dict], tools: list[dict]) -> dict: ...

# ---- Tools (Recommendation 3): bash + 3 file tools --------------------------
def _truncate(s: str, n: int = 25_000) -> str: return s if len(s) <= n else s[:n] + "\n...[truncated]"

def tool_bash(cmd: str) -> str:
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=120)
    return _truncate(f"exit={r.returncode}\nstdout:\n{r.stdout}\nstderr:\n{r.stderr}")

def tool_read_file(path: str, offset: int = 0, limit: int = 2000) -> str:
    lines = pathlib.Path(path).read_text().splitlines()
    return _truncate("\n".join(f"{i+1:4}: {ln}" for i, ln in enumerate(lines[offset:offset+limit])))

def tool_write_file(path: str, content: str) -> str:
    pathlib.Path(path).write_text(content); return f"wrote {len(content)} bytes"

def tool_search_replace(path: str, search: str, replace: str) -> str:
    p = pathlib.Path(path); text = p.read_text()
    if text.count(search) != 1: return f"ERROR: search string occurs {text.count(search)}x; must be unique"
    p.write_text(text.replace(search, replace, 1)); return "OK"

TOOLS = {"bash": tool_bash, "read_file": tool_read_file,
         "write_file": tool_write_file, "search_replace": tool_search_replace}

# ---- Hierarchical Markdown context (Recommendation 6) ----------------------
def discover_context(cwd: pathlib.Path = pathlib.Path.cwd()) -> str:
    parts = []
    for p in reversed([cwd, *cwd.parents]):                     # root-to-leaf
        md = p / "AGENTS.md"
        if md.exists(): parts.append(f"<ctx path='{md}'>\n{md.read_text()}\n</ctx>")
    return "\n".join(parts)

# ---- Middleware pipeline (Recommendation 1) ---------------------------------
@dataclass
class Agent:
    model: Model
    messages: list = field(default_factory=list)
    n_turns: int = 0
    cost: float = 0.0
    max_turns: int = 50
    max_cost: float = 5.00
    compact_at_tokens: int = 120_000

    def _token_estimate(self) -> int:
        return sum(len(json.dumps(m)) for m in self.messages) // 4

    async def _check_limits(self):
        if self.n_turns >= self.max_turns: raise StopIteration(f"max_turns={self.max_turns}")
        if self.cost   >= self.max_cost:   raise StopIteration(f"max_cost=${self.max_cost:.2f}")

    async def _maybe_compact(self):                             # Recommendation 7
        if self._token_estimate() < self.compact_at_tokens: return
        summary = await self.model.complete(
            self.messages + [{"role": "user",
                "content": "Summarize the conversation. Preserve decisions and unresolved issues."}], tools=[])
        # Preserve system + last 30% of turns verbatim, drop the middle (Gemini pattern)
        keep = max(4, int(len(self.messages) * 0.30))
        self.messages = [self.messages[0], {"role":"assistant","content":summary["content"]}] + self.messages[-keep:]

    async def run(self, task: str) -> str:
        self.messages = [
            {"role": "system", "content": f"You are a SWE agent.\n\n{discover_context()}"},
            {"role": "user",   "content": task},
        ]
        tool_schemas = [{"name": n, "description": f.__doc__ or n} for n, f in TOOLS.items()]
        while True:
            await self._check_limits(); await self._maybe_compact()
            resp = await self.model.complete(self.messages, tools=tool_schemas)
            self.n_turns += 1; self.cost += resp.get("cost", 0.0)
            self.messages.append(resp)
            if not resp.get("tool_calls"): return resp.get("content", "")
            for call in resp["tool_calls"]:                     # serial execution
                try:    out = TOOLS[call["name"]](**call["args"])
                except Exception as e: out = f"ERROR: {type(e).__name__}: {e}"
                self.messages.append({"role": "tool", "tool_call_id": call["id"], "content": _truncate(str(out))})
```

The paper is explicit that the scaffold "deliberately omits features for which
our corpus shows divergence: sandbox (Recommendations 9–10 are deployment-specific),
multi-agent (Recommendation 12 defers it), MCP/Skills (Recommendation 14 is
extensibility, not core)." Observation 13: it "implements 10 of the 18
recommendations directly and is compatible with the remaining eight."

---

## 10. Applicability to PocketHarness

Our constraints: **Android client; OpenAI-compatible chat-completions HTTP;
deepseek-v4-flash; single agent; messaging-thread session UI; minimal tool set;
2-day build budget.** The paper is the best-matched of our three references
because its own floor (Mini-SWE-Agent, Pi) is a *structural* argument, not an
inventory one.

### Adopt — high value, low cost

1. **The linear loop, verbatim (Rec 1).** A `while` over one chat-completions
   call with a tool dispatch step is the paper's own starting point and the
   corpus's floor. Do not build a middleware pipeline on day one; add a light
   policy list (turn cap, cost cap, compaction trigger) only when you have ≥3
   independent policies.
2. **The four-tool surface (Rec 3, our version).** The scaffold's bash /
   read_file / write_file / search_replace maps almost 1:1 onto an Android
   harness — swap `bash` for a sandboxed command runner or drop it entirely if we
   are not executing on-device. Start at 3–4 tools, not 15. Below ~15 tools
   deferred loading is explicitly not worth it (Rec 4), so skip it.
3. **Prompt cache hygiene (Obs 2, Pattern 11).** Put the system preamble, tool
   schemas, and persona in a byte-stable prefix; append the thread history as a
   volatile tail. deepseek-v4-flash is DeepSeek-family, and Hermes's own
   model-family gating sends tool-use enforcement blocks to DeepSeek — a concrete
   precedent that the model benefits from explicit tool-use enforcement prose.
   Cache hits are the cheapest latency win on a mobile client.
4. **Policy-as-code with a floor (Rec 11, Pattern 2).** Even without OS
   sandboxing, keep the approval/denial rules in a config file (or a frozen
   Kotlin object) and keep a small *never-allowed* set frozen at app start, not
   relaxable by anything the model or a fetched web page says. Hermes's frozen
   `--yolo` variable is the design lesson: an injected string must not be able to
   flip our floor.
5. **Untrusted-content delimiting (Pattern 28).** Any web fetch, file paste, or
   MCP-ish result the app renders must be wrapped and marked as untrusted before
   it re-enters the prompt. Hermes/OpenHands inject `<untrusted_tool_result>` /
   `<UNTRUSTED_CONTENT>` markers; this costs a few lines and is the paper's
   clearest content-borne-threat control.

### Adopt — one step up, still in budget

6. **Threshold compaction with an incremental summary (Rec 7).** Messaging
   threads grow; a fixed trigger, a preserved verbatim recent tail, and passing
   the previous summary back for merging (Pi/OpenCode) is ~30 lines. For a
   mobile client, also cap the raw rendered thread.
7. **Cheap stuck caps (Rec 18).** A SHA-256 of tool name + sorted args, warning
   after two identical calls, is "a dozen lines." Hermes ships the hard stop
   *disabled*; match that posture — warn, do not kill.
8. **Do the client/server split with us as the client (Pattern 24).** The paper
   documents harnesses exposing an embedded server so every UI is a client. We are
   the mirror image: our Android app is a client of an OpenAI-compatible endpoint.
   That means our session model should treat "the model" as a service with
   transport-level failure modes (the paper's ~23-value FailoverReason enum in
   Hermes is the maximal form), and our UI should render streaming deltas rather
   than block on full responses.
9. **Messaging-thread UI is already the paper's session-substrate intuition.**
   Pi's session tree and OpenCode's log-as-queue both treat the *transcript* as
   the durable artifact. Our messaging threads are that artifact; persist them
   append-only and let resume/replay fall out of it.

### Out of scope for PocketHarness

- **Multi-agent orchestration (Rec 12, Rec 13, Obs 7, Patterns 3/12/18/22).**
  Stay single-agent; the paper explicitly says to wait for a concrete
  breadth-first phase. Rec 13's ACP server is for *harnesses that want to be
  hosted* — irrelevant to a client that consumes an endpoint. Do not build it.
- **Agent-maintained / self-improving memory (Obs 5, Patterns 18/20).** Codex's
  consolidation sub-agent and Hermes's self-authoring skills are multi-thousand-line
  investments; they need a persistent exec environment we do not have.
- **OS-level sandboxing (Rec 10).** We are not executing untrusted code on-device
  in v1; Rec 9's mode selector is our tier.
- **Deferred tool loading (Rec 4), model-family prompt matrices (Pattern 25),
  cache-dialect fanout (Pattern 26), syntax-aware command permissioning
  (Pattern 27), harness mimicry (Pattern 29).** All are responses to problems a
  multi-provider, multi-tool, multi-tenant harness has and a single-model mobile
  client does not. Harness mimicry in particular is a ToS-risky hack, not a feature.

### Actively wrong for our context (do not import the assumption)

- **Do not build code RAG any more than the corpus did (Rec 8/16, Absence 2).**
  0/11 systems embed code; even on-device, our retrieval should be lexical
  (FTS over the thread store, grep-like search over any workspace) plus
  hierarchical Markdown context files. Hermes's SQLite FTS5 is the production
  ceiling for exactly this.
- **Do not adopt a framework (Rec 15, Absence 1).** For a 2-day Android build
  this is not even a temptation, but the point generalizes: hand-roll the loop so
  the prompts and responses stay transparent, which is also the paper's stated
  reason production harnesses do so.
- **Do not over-generalize the paper's *inventory* claims.** Table 4's tool
  counts, Table 17's feature matrix, and every version pin will be stale within
  weeks (Obs 12, §14.5). Copy structure.
- **Do not treat Obs 10 (Anthropic-guidance alignment) as proof our design is
  right.** The authors explicitly refuse causation; it is "suggestive," with
  interview work named as the missing follow-up (§15.6).
- **Do not read "platform turn" as a mandate to build a platform.** Obs 12
  describes the *market's* trajectory, not a requirement for a good harness. The
  paper's own floor is Mini-SWE-Agent.

### Two-day build order (concrete)

Day 1: threads UI + streaming client to the OpenAI-compatible endpoint; the
linear loop; 3 tools (read/search/write or read-only + a fetch); stable system
prefix with the persona and tool schemas, volatile tail for thread history;
persist threads append-only. Day 2: threshold compaction with incremental
summary; warn-first signature hash; approval/denial config with a frozen floor;
untrusted-content markers on any fetched or pasted content; a DeepSeek-family
tool-use-enforcement prose block (per the Hermes precedent) plus a
verify-before-claim instruction — then stop. Resist every temptation the paper
catalogues on the maximal side of Table 1.

---

## Sources

1. Barbaste, P., Darrigol, T., Vu, G., & Wiltberger, T. *Harness Engineering:
   Anatomy, Architecture, and Evolution of Coding Agents — A Source-Code Study of
   Eleven Systems.* arXiv:2609.00006v1 [cs.SE], 15 Jul 2026.
   https://arxiv.org/html/2609.00006v1 · PDF: https://arxiv.org/pdf/2609.00006
   (full clean-text cache: `~/.hermes/profiles/dev/cache/web/arxiv.org-6c073d0504.md`).
2. Prior internal note (background only, scoped to two non-coding harnesses):
   `/home/vruizes/projects/arxiv-2609.00006-vs-harness-vox.md`. Independently
   confirmed against the paper: the seven-subsystem list and its names; Obs 9 as
   the twin absences; Obs 8 skills 9/11 > MCP 8/11; the no-framework and
   no-code-RAG results; Rec 15's framework list; Rec 12 single-agent; Rec 18's
   "cheap caps … a dozen lines"; Rec 4's ~15-tool deferred-loading threshold;
   the 90-line scaffold; Hermes's SQLite FTS5 "no LLM calls anywhere" and
   three-tier prompt; the policy-migrates-prose→config trend (Obs 3, §14.5); the
   Codex-adopts-Claude-Code-hooks/importer example. Not verifiable against the
   paper (they are the note author's own scoping opinions, not paper claims):
   that the platform-turn thesis, file-edit machinery, repo-context, and SWE-bench
   arguments "do NOT transfer"; the project-specific mapping tables; and the claim
   that pydantic-ai is used by a corpus member as a model SDK (the paper lists
   Pydantic AI in Rec 15's do-not-use list and separately notes a *Pydantic AI
   Harness* product in the framework→harness merger, but never audit a system
   that runs on pydantic-ai as its runtime). No outright factual error was found
   in the note; its caveat that the coding-specific machinery may not transfer is
   its own judgment, and this brief treats that machinery as in-scope for
   PocketHarness precisely because PocketHarness *is* a coding harness.
3. Paper's own key references cited here: Anthropic *Building Effective Agents*
   (Schluntz & Zhang, Dec 2024), *Effective Context Engineering* (Rajasekaran et
   al., Sept 2025), *Writing Effective Tools* (Aizawa et al., Sept 2025), *How We
   Built Our Multi-Agent Research System* (Hadfield et al., June 2025); Lin et
   al. *Agentic Harness Engineering* (arXiv:2604.25850, Apr 2026); Rombaut
   *Inside the Scaffold* (arXiv:2604.03515, Apr 2026); Macedo *What makes a
   harness a harness* (arXiv:2606.10106, June 2026).
