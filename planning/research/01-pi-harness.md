# Pi Coding Agent (pi.dev) — Harness, System Prompt, and Tool Surface

Reference brief for PocketHarness (minimal Android coding-harness app). Every concrete claim below is traced to a file path or URL actually read during research. Where Pi's shipped bundle and its `main` branch docs disagree, that is called out explicitly. Local install verified on this machine:

- CLI entry: `/mnt/c/Users/vruizes/AppData/Local/pi-node/current/node_modules/@earendil-works/pi-coding-agent/dist/cli.js` (launched by `/mnt/c/Users/vruizes/AppData/Local/pi-node/current/pi`)
- Installed version: **0.84.1** (`.../pi-coding-agent/package.json`, CHANGELOG entry `## [0.84.1] - 2026-08-07`)
- Config/skills dir: `/home/vruizes/.pi/agent/skills/` (symlinks into `/home/vruizes/.agents/skills/`)
- Public repo: `https://github.com/earendil-works/pi` (self-described "AI agent toolkit")

---

## 1. What Pi is and its design philosophy

Pi is a **minimal terminal coding harness**: a TypeScript/Node CLI (`@earendil-works/pi-coding-agent`) whose stated design is a small core extended through TypeScript extensions, skills, prompt templates, themes, and "pi packages." Its own docs open with: *"Pi is a minimal terminal coding harness. It is designed to stay small at the core while being extended through TypeScript extensions, skills, prompt templates, themes, and pi packages."* (`docs/index.md`). The `<title>`/landing copy on pi.dev adds: *"Pi supports skills, AGENTS.md files and is very token efficient due to its minimal system prompt."*

The philosophy is stated most sharply in `docs/usage.md` §Design Principles:

> *"Pi keeps the core small and pushes workflow-specific behavior into extensions, skills, prompt templates, and packages. It intentionally does not include built-in MCP, sub-agents, permission popups, plan mode, to-dos, or background bash. You can build or install those workflows as extensions or packages, or use external tools such as containers and tmux."*

The arXiv source-code study (`arXiv:2609.00006`, §11.8/§14) confirms Pi as *"the poster child of the opposite, minimal-core countertrend"* — the corpus's only system that *"documents the absence of safety infrastructure as a design principle"* and *"deliberately ships single-agent, relegating sub-agents to extension space."* Its loop is *"the purest iterative implementation in the corpus after Mini-SWE-Agent: a ~790-line functional core with no planner, no reflection step, no turn cap, no stuck detection, and no cost kill-switch—the loop runs until the model stops calling tools, and all of those absences are documented design refusals."*

**Important drift note:** the docs site and repo `main` are newer than the installed 0.84.1 bundle. The system-prompt persona string and the eight-tool set are identical in both (verified by grepping `dist/core/system-prompt.js` locally vs `src/core/system-prompt.ts`), so prompt/tool claims here are stable across versions; session-format and provider details are sourced from `main` docs.

---

## 2. Agent loop

Source of truth: `packages/agent/src/agent-loop.ts` and `packages/agent/src/agent.ts` (repo `main`). Pi's low-level loop is deliberately thin and works on its own `AgentMessage` type, converting to provider `Message[]` only at the LLM-call boundary.

Turn cycle (`runLoop` in `agent-loop.ts`):

1. **`agent_start`** emitted once, then **`turn_start`**. Prompt messages are echoed as `message_start`/`message_end`.
2. **Stream assistant response** (`streamAssistantResponse`): optional `transformContext` (AgentMessage→AgentMessage) → `convertToLlm` (AgentMessage→LLM Message[]) → build `Context {systemPrompt, messages, tools}` → resolve API key **per call** (for expiring OAuth tokens) → call `streamFn` → `for await (const event of response)`. Provider events (`text_start/delta/end`, `thinking_*`, `toolcall_start/delta/end`) are re-emitted as `message_update`; a partial assistant message is pushed into context immediately and replaced by the final message on `done`/`error`.
3. **Tool-call detection:** `message.content.filter(c => c.type === "toolCall")`. If `stopReason === "length"` (output truncated), **all** tool calls in that message are failed with a synthetic error result ("…arguments may be truncated. Re-issue the tool call…") rather than executed — a deliberate guard because Pi uses a **best-effort JSON salvage parser** for streamed tool arguments that can validate while silently incomplete (`failToolCallsFromTruncatedMessage`).
4. **Execute tool batch** — default mode is **parallel** (`toolExecution: "parallel"`): preflight/validate sequentially, then run allowed tools concurrently; `tool_execution_end` fires in completion order, while result messages are appended to context in assistant source order. A tool with `executionMode: "sequential"` forces the whole batch sequential. Per call: `prepareToolCall` (find tool → `prepareArguments` compat shim → schema validation → `beforeToolCall` hook which may `{block:true}`) → `execute(toolCallId, args, signal, onUpdate)` → `afterToolCall` hook (may replace `content`/`details`/`isError`/`usage`).
5. **`turn_end`**, then `shouldStopAfterTurn?` (extension stop hook). If it returns true → `agent_end`, exit.
6. **Steering/follow-up queues:** after a turn, Pi drains *steering* messages (injected mid-run after tool calls finish) and, when the model would otherwise stop, *follow-up* messages (injected after all work). Two nested loops implement this: an inner loop while `hasMoreToolCalls || pendingMessages.length > 0`, and an outer loop for follow-ups. Messages are queue modes `"all"` or `"one-at-a-time"` (`steeringMode`/`followUpMode`, `PendingMessageQueue`).
7. **Termination:** the inner loop ends when the assistant message contains no tool calls (and no steering); the outer loop ends when no follow-ups remain → **`agent_end`** with `messages`. There is **no turn cap, no planning step, no reflection step, no stuck detection.**

Abort: an `AbortController` signal is threaded through every tool; `Escape` aborts and each tool is responsible for honoring the signal (most check `signal.aborted` after each await). Events emitted: `agent_start`, `turn_start`, `message_start|update|end`, `tool_execution_start|update|end`, `turn_end`, `agent_end` (`packages/agent/src/types.ts`).

---

## 3. System prompt

Reconstructed verbatim from `packages/coding-agent/src/core/system-prompt.ts` (`buildSystemPrompt`). **Static skeleton** (the literal template) with the default tool set `["read","bash","edit","write"]` and the default working directory:

```text
You are an expert coding assistant operating inside pi, a coding agent harness. You help users by reading files, executing commands, editing code, and writing new files.

Available tools:
- read: Read file contents
- bash: Execute bash commands (ls, grep, find, etc.)
- edit: Make precise file edits with exact text replacement, including multiple disjoint edits in one call
- write: Create or overwrite files

In addition to the tools above, you may have access to other custom tools depending on the project.

Guidelines:
- Use bash for file operations like ls, rg, find
- Use read to examine files instead of cat or sed.
- You can inspect PI_* environment variables for current model and session details.
- Use edit for precise changes (edits[].oldText must match exactly)
- When changing multiple separate locations in one file, use one edit call with multiple entries in edits[] instead of multiple edit calls
- Each edits[].oldText is matched against the original file, not after earlier edits are applied. Do not emit overlapping or nested edits. Merge nearby changes into one edit.
- Keep edits[].oldText as small as possible while still being unique in the file. Do not pad with large unchanged regions.
- Use write only for new files or complete rewrites.
- Be concise in your responses
- Show file paths clearly when working with files

Pi documentation (read only when the user asks about pi itself, its SDK, extensions, themes, skills, or TUI):
- Main documentation: <abs path to README.md>
- Additional docs: <abs path to docs/>
- Examples: <abs path to examples/> (extensions, custom tools, SDK)
- When reading pi docs or examples, resolve docs/... under Additional docs and examples/... under Examples, not the current working directory
- When asked about: extensions (docs/extensions.md, examples/extensions/), themes ..., skills ..., prompt templates ..., TUI components ..., keybindings ..., SDK integrations ..., custom providers ..., adding models ..., pi packages ..., environment variables ...
- When working on pi topics, read the docs and examples, and follow .md cross-references before implementing
- Always read pi .md files completely and follow links to related docs (e.g., tui.md for TUI API details)

Current working directory: <cwd>
```

**Static vs dynamic assembly:** the template is static; everything between the fixed labels is assembled at build time from data:
- **Persona + guidelines + docs section**: static strings.
- **`Available tools` list**: `visibleTools` = selected tools that have a registered `promptSnippet`; each tool contributes its own one-liner. Tools with no snippet are hidden from the prompt.
- **`Guidelines`**: assembled from (a) a conditional bash/powershell file-ops hint when grep/find/ls are absent, (b) each active tool's `promptGuidelines` (deduplicated via a `Set`), (c) user/extension `promptGuidelines`, (d) two always-on bullets: `"Be concise in your responses"` and `"Show file paths clearly when working with files"`.
- **`⟨project_context⟩`**: appended as `<project_instructions path="...">…</project_instructions>` blocks from discovered `AGENTS.md`/`CLAUDE.md` (or `AGENTS.override.md`).
- **Skills block**: appended as an XML index when a `read` or `bash` tool is available.
- **`Current working directory`**: appended last.
- `customPrompt` (from `.pi/SYSTEM.md` / `~/.pi/agent/SYSTEM.md` / `--system-prompt`) **replaces** the template entirely but context files and skills are still appended; `APPEND_SYSTEM.md` / `--append-system-prompt` appends.

**Length/format:** the builder is documented (arXiv §) as *"a ~170-line builder emitting 30–40 lines: persona, tool list, deduplicated per-tool guideline snippets (each tool contributes promptSnippet / promptGuidelines, so the prompt co-varies with the active toolset), ⟨project_context⟩ from AGENTS.md."* My reconstruction with the default 4-tool set measures **2,401 chars / 32 lines / 354 words ≈ ~600 tokens (chars/4 estimate)** including the docs-reference block and a placeholder cwd. Formatting is plain Markdown (no XML around the persona), one bullet per rule, minimal prose. Pi deliberately omits policy prose: arXiv notes *"Pi is the deliberate exception: its prompt contains no such directive, policy being delegated wholesale to user-supplied AGENTS.md and extensions,"* and its concision rule is a single bullet (*"Be concise in your responses"*), with **no emoji directive** at all.

---

## 4. Tool surface

Source: `packages/coding-agent/src/core/tools/*.ts` and `index.ts`. `allToolNames` = exactly eight: `read, bash, powershell, edit, write, grep, find, ls`. **Total built-in tools: 8 (7 on non-Windows; `powershell` is Windows-only).** **Default-exposed set: 4** — `createCodingToolDefinitions` returns `[read, bash, edit, write]`. A read-only preset exists (`createReadOnlyToolDefinitions` = `read, grep, find, ls`), selectable via `--tools read,grep,find,ls`.

Descriptions are verbatim from the source (with `${DEFAULT_MAX_LINES}=2000`, `${DEFAULT_MAX_BYTES}=50*1024` → "50KB", `${GREP_MAX_LINE_LENGTH}=500` substituted; confirmed in `dist/core/tools/truncate.js`).

| Tool | Parameters | Description (verbatim) | Prompt snippet |
|---|---|---|---|
| **read** | `path` (string, "Path to the file to read (relative or absolute)"); `offset?` (number, "Line number to start reading from (1-indexed)"); `limit?` (number, "Maximum number of lines to read") | "Read the contents of a file. Supports text files and images (jpg, png, gif, webp, bmp). Images are sent as attachments. For text files, output is truncated to 2000 lines or 50KB (whichever is hit first). Use offset/limit for large files. When you need the full file, continue with offset until complete." | "Read file contents" |
| **bash** | `command` (string, "Shell command to execute"); `timeout?` (number, "Timeout in seconds (optional, no default timeout)") | "Execute a bash command in the current working directory. Returns stdout and stderr. Output is truncated to last 2000 lines or 50KB (whichever is hit first). If truncated, full output is saved to a temp file. Optionally provide a timeout in seconds." | "Execute bash commands (ls, grep, find, etc.)" |
| **powershell** | same schema as `bash` | Same as bash with "PowerShell" substituted; UTF-8 output prefix injected. Windows-only. | "Execute PowerShell commands" |
| **edit** | `path` (string, "Path to the file to edit (relative or absolute)"); `edits` (array of `{oldText: string, newText: string}`) | "Edit a single file using exact text replacement. Every edits[].oldText must match a unique, non-overlapping region of the original file. If two changes affect the same block or nearby lines, merge them into one edit instead of emitting overlapping edits. Do not include large unchanged regions just to connect distant changes." | "Make precise file edits with exact text replacement, including multiple disjoint edits in one call" |
| **write** | `path` (string, "Path to the file to write (relative or absolute)"); `content` (string, "Content to write to the file") | "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Automatically creates parent directories." | "Create or overwrite files" |
| **grep** | `pattern` (string, "Search pattern (regex or literal string)"); `path?` (string); `glob?` (string, "Filter files by glob pattern, e.g. '*.ts' or '**/*.spec.ts'"); `ignoreCase?` (bool); `literal?` (bool); `context?` (number); `limit?` (number, default 100) | "Search file contents for a pattern. Returns matching lines with file paths and line numbers. Respects .gitignore. Output is truncated to 100 matches or 50KB (whichever is hit first). Long lines are truncated to 500 chars." | "Search file contents for patterns (respects .gitignore)" |
| **find** | `pattern` (string, "Glob pattern to match files, e.g. '*.ts', '**/*.json', or 'src/**/*.spec.ts'"); `path?` (string); `limit?` (number, default 1000) | "Search for files by glob pattern. Returns matching file paths relative to the search directory. Respects .gitignore. Output is truncated to 1000 results or 50KB (whichever is hit first)." | "Find files by glob pattern (respects .gitignore)" |
| **ls** | `path?` (string, "Directory to list (default: current directory)"); `limit?` (number, default 500) | "List directory contents. Returns entries sorted alphabetically, with '/' suffix for directories. Includes dotfiles. Output is truncated to 500 entries or 50KB (whichever is hit first)." | "List directory contents" |

Notes:
- **`grep`/`find` shell out** to `rg`/`fd` (auto-downloaded via `ensureTool` if missing); `edit`/`write`/`read`/`ls` are pure Node fs.
- **`constrainedSampling: { type: "json_schema", strict: "prefer" }`** on read/bash/edit/write — Pi prefers provider-native strict JSON tool schemas, falling back gracefully.
- **`ask_question` is NOT a built-in tool.** It appears only in CLI help text as an arbitrary example (`dist/cli/args.js:342` → `pi --exclude-tools ask_question`) and in `docs/usage.md`. It is the kind of interactive tool an *extension* registers.
- **Flags/modes:** `--tools/-t` (allowlist), `--exclude-tools/-xt`, `--no-builtin-tools/-nbt`, `--no-tools/-nt`. Extension and custom tools sit alongside the eight built-ins and can be excluded the same way.
- Every tool exposes a **pluggable `Operations` interface** (`ReadOperations`, `BashOperations`, `EditOperations`, `WriteOperations`, `GrepOperations`, `FindOperations`, `LsOperations`) — arXiv calls this *"the single remoting seam through which SSH, container, and micro-VM extensions relocate execution without touching the tools."*

---

## 5. Context management

Source: `docs/compaction.md`. Two mechanisms, both producing structured Markdown summaries with **cumulative file tracking** (`{readFiles, modifiedFiles}`):

| Mechanism | Trigger | Purpose |
|---|---|---|
| Compaction | `contextTokens > contextWindow − reserveTokens`, or `/compact` | Summarize old messages |
| Branch summarization | `/tree` navigation | Preserve context of an abandoned branch |

- **Threshold:** default `reserveTokens = 16384`, `keepRecentTokens = 20000` (both in `~/.pi/agent/settings.json` or `<project>/.pi/settings.json`; per-model overrides via `compaction.modelOverrides`). arXiv confirms: *"Pi triggers at contextWindow − 16,384 tokens (keeping 20K recent)."*
- **Cut algorithm:** walk backwards from newest accumulating token estimates until `keepRecentTokens`; cut only at user/assistant/bashExecution/custom messages — **never at a tool result** (results must stay with their tool call). Then call the LLM to summarize with a structured format, passing the **previous summary as iterative context** (arXiv: *"summaries are iteratively merged—the update prompt re-ingests the previous summary rather than re-summarizing from scratch"*).
- **Rebuild:** the LLM sees `system → compaction summary → messages from firstKeptEntryId onward`. Older summarized entries are dropped from context but remain in the file.
- **Split turns:** if one turn exceeds `keepRecentTokens`, the cut lands mid-turn; Pi generates two summaries (history + turn-prefix) and merges them.
- **When it runs:** mid-run Pi checks the threshold *after tools finish and results are appended, before the next assistant response*, and compacts **inside the same agent run**; it also checks before a new user prompt and after a run ends.
- **Extension hooks:** `session_before_compact` and `session_before_tree` let extensions veto or replace the summary; generated summaries store their LLM `usage` so session totals include summarization cost. Compaction/branch-summary requests use fresh routing session IDs and disable prompt-cache writes.
- **Truncation:** independent of compaction, every tool output is capped at 2000 lines / 50KB (grep matches capped at 100, find at 1000, ls at 500 entries; grep lines at 500 chars). `bash` overflow is written to a temp file whose path is reported back; `read` overflow returns an `[Showing lines X–Y of N. Use offset=Z to continue.]` notice.
- **What persists:** the full transcript (all entries) is always on disk; only the LLM-facing context is compacted.

---

## 6. Sessions / threads

Source: `docs/session-format.md`, `docs/sessions.md`. This is the highest-value section for PocketHarness's thread UI.

- **Format:** **JSONL** (one JSON object per line), each line an entry with a `type`. Entries form a **tree** via `id`/`parentId` (8-char hex id normally), enabling in-place branching without new files.
- **Location:** `~/.pi/agent/sessions/--<path>--/<timestamp>_<session-id>.jsonl`, grouped by working directory (`<path>` = cwd with separators/`:` replaced by `-`, leading separator stripped). `<session-id>` is a UUID by default.
- **Header (first line, not in the tree):** `{"type":"session","version":3,"id":"uuid","timestamp":"ISO","cwd":"/path"}`; forked sessions add `"parentSession": "/path/original.jsonl"`. Versions: v1 linear (legacy), v2 tree, v3 renamed `hookMessage`→`custom`. Auto-migrated to v3 on load.
- **Entry shape:** every non-header entry extends `SessionEntryBase` = `{type, id, parentId, timestamp(ISO)}`.
- **Entry types:** `message` (contains an `AgentMessage`), `model_change` (`{provider, modelId}`), `thinking_level_change`, `compaction` (`{summary, firstKeptEntryId, tokensBefore, usage?, details?}`), `branch_summary` (`{summary, fromId, …}`), `custom` (extension state, **not** in LLM context), `custom_message` (extension message, **in** context), `label` (bookmark on `targetId`), `session_info` (`{name}`).
- **Message roles** (`AgentMessage` union): `user`, `assistant`, `toolResult`, `bashExecution`, `custom`, `branchSummary`, `compactionSummary`. Content is an array of typed blocks: `text`, `image` (base64 + mimeType), `thinking`, `toolCall` (`{id, name, arguments}`). Assistant messages carry `api/provider/model/usage/stopReason` where `stopReason ∈ {pending, stop, length, toolUse, error, aborted, deferred}`; `pending` is streaming-only and should never be persisted. Tool results carry `toolCallId`, `toolName`, `content`, `isError`.
- **Resume:** `pi -c` (continue most recent), `pi -r` (picker), `--session <path|id>`, `--fork <path|id>`, and `SessionManager.continueRecent(cwd)` / `.open(path)` / `.list(cwd)` / `.listAll()`. In-file tree navigation via `/tree`; `/fork` and `/clone` create new files. `SessionManager.inMemory()` gives no-persistence sessions.
- **Why the tree matters for a messaging UI:** every entry already has a stable `id`/`parentId`, so a flat "message list per session" is just one path through the tree. Branching is native and cheap (`branch(entryId)`, `getBranch`, `getChildren`).

---

## 7. Extension surface

Sources: `docs/extensions.md`, `docs/skills.md`, `docs/usage.md`.

- **Skills** (`docs/skills.md`): implement the **agentskills.io** standard. A skill is a directory with `SKILL.md` (YAML frontmatter: required `name`, `description`; optional `license`, `compatibility`, `metadata`, `allowed-tools`, `disable-model-invocation`). Loaded from `~/.pi/agent/skills/`, `~/.agents/skills/`, project `.pi/skills/` and `.agents/skills/`, packages, settings, and `--skill`. **Progressive disclosure:** only name+description are eagerly placed in the system prompt (XML index); the body loads on demand — most minimally via the ordinary **`read` tool** (Pi does not add a dedicated Skill tool). Pi is lenient: name need not match parent dir; violations warn but still load. Verified locally: `/home/vruizes/.agents/skills/browser-act/SKILL.md` shows the real frontmatter (`name`, `description`, `allowed-tools: Bash(browser-act:*)`, `metadata`).
- **Extensions** (`docs/extensions.md`): TypeScript modules auto-discovered from `~/.pi/agent/extensions/*.ts` (global) or `.pi/extensions/*.ts` (project, post-trust), loaded via jiti (no build step). `ExtensionAPI` supports `pi.registerTool()`, `registerCommand()`, `registerShortcut()`, `registerFlag()`, `registerProvider()`, `appendEntry()` (session persistence), plus event subscription. The event bus is large — arXiv: *"Pi is an event bus, with ~33 typed events spanning tool interception to raw provider I/O."* Documented lifecycle events include `project_trust`, `session_start/shutdown`, `resources_discover`, `input`, `before_agent_start`, `agent_start/end`, `turn_start/end`, `message_*`, `context`, `before_provider_headers`, `before_provider_request`, `after_provider_response`, `tool_execution_*`, `tool_call`, `tool_result`, `session_before_compact`, `session_before_tree`.
- **Conventions / AGENTS.md** (`docs/usage.md`): Pi loads `AGENTS.md` or `CLAUDE.md` from `~/.pi/agent/`, walking up parent dirs, and cwd (`AGENTS.override.md` wins per-directory). `SYSTEM.md`/`APPEND_SYSTEM.md` replace/append the system prompt. **Project trust** gates project-local settings/extensions/skills; non-interactive modes use `defaultProjectTrust`.
- **Prompt templates, themes, packages** round out the extension surface; packages bundle extensions/skills/prompts/themes (npm/git).
- **MCP: explicitly rejected.** arXiv: *"The corpus's one principled holdout is Pi, whose documentation rejects MCP outright—'build CLI tools with READMEs'—making skills-plus-shell its articulated alternative to a wire protocol."* Pi ships **no built-in MCP client.**

---

## 8. Model / provider layer

Sources: `docs/providers.md`, `docs/models.md`, `docs/custom-provider.md`.

- **No single provider abstraction leaking into the core; a per-provider conditioning layer instead.** Pi has **nine wire-protocol implementations** (arXiv: *"Pi: nine wire-protocol implementations with a partial-json salvage parser for streamed tool arguments"*). API types (`docs/custom-provider.md`): `anthropic-messages`, `openai-completions`, `openai-responses`, `azure-openai-responses`, `openai-codex-responses`, `mistral-conversations`, `google-generative-ai`, `google-vertex`, `bedrock-converse-stream`.
- **OpenAI-compatible endpoints (directly relevant to PocketHarness):** configure in `~/.pi/agent/models.json` or via an extension `pi.registerProvider()`. The pattern for any OpenAI Chat Completions-compatible server (Ollama, vLLM, LM Studio, SGLang, DeepSeek, etc.):
  ```json
  { "providers": { "ollama": {
      "baseUrl": "http://localhost:11434/v1",
      "api": "openai-completions",
      "apiKey": "ollama",
      "compat": { "supportsDeveloperRole": false, "supportsReasoningEffort": false },
      "models": [ { "id": "qwen2.5-coder:7b" } ] } } }
  ```
  Per-model fields: `id`, `name`, `api`, `reasoning`, `thinkingLevelMap`, `input`, `contextWindow` (default 128000), `maxTokens` (default 16384), `samplingParams`, `cost`, `compat`. A model only needs `id`.
- **`compat` is the crux of OpenAI-compat support** — quirky servers need explicit flags: `supportsDeveloperRole:false` (send `system`, not `developer`), `supportsReasoningEffort`, `maxTokensField: "max_tokens" | "max_completion_tokens"`, `requiresToolResultName`, `requiresAssistantAfterToolResult`, `thinkingFormat` (`openai|openrouter|deepseek|together|qwen|chat-template|qwen-chat-template|string-thinking|…`), `thinkingTokenBudgetField`, `cacheControlFormat:"anthropic"`, `sessionAffinityFormat`. **DeepSeek specifically:** `thinkingFormat: "deepseek"` sends `thinking: {type:"enabled"|"disabled"}` plus `reasoning_effort` when enabled — and the `models.md` examples literally use `"id": "deepseek-v4-flash"` with sample `samplingParams`.
- **Credentials:** `auth.json` (0600) > env vars > `models.json` keys; values support `!command`, `$ENV` interpolation, and literals. OAuth via `/login` for subscription providers.
- **Cost/caching:** Pi places explicit prompt-cache breakpoints with TTL tiers and *"audits per-turn cache-miss dollar waste as a first-class metric"* (arXiv) — a cost-aware design worth noting but likely out of scope for a minimal Android harness.

---

## 9. Explicitly minimalist about Pi (concrete decisions, with evidence)

1. **Eight built-in tools, four exposed by default** (`read, bash, edit, write`). Everything else (`grep/find/ls/powershell`) is opt-in; arXiv's July-2026 spectrum counts *"7 Pi"* (excluding Windows-only powershell) with *"7 tools built, four exposed by default, everything else an extension"* (`Figure 3`).
2. **~30–40-line / ~600-token default system prompt** where the persona is two sentences and the only universal quality rule is *"Be concise in your responses."* arXiv: *"near-total delegation to user context (Pi's 30-line prompt)."*
3. **~790-line agent loop with no planner, no reflection, no turn cap, no stuck detection, no cost kill-switch.** It "runs until the model stops calling tools," and these absences are *documented design refusals.*
4. **Single-agent core — no spawn/task/subagent tool.** Sub-agents exist only as a ~1,000-line reference extension.
5. **No built-in MCP, no permission popups, no plan mode, no to-dos, no background bash.** Explicit in `docs/usage.md` §Design Principles.
6. **Skills implemented as agentskills.io + the ordinary `read` tool** — no dedicated Skill tool, no protocol.
7. **Tools, not a framework:** sub-agents are "CLI tools with READMEs" / extensions; execution relocation is a single `Operations` interface seam.
8. **Policy lives in `AGENTS.md`, not the prompt** — the prompt carries no safety/scratchpad/emoji prose.

---

## 10. Lessons for PocketHarness

**Worth borrowing:**
1. **Ship exactly four tools: `read`, `write`/`edit`, and a `bash`-equivalent.** Pi proves a working SWE agent needs no more. For Android, `bash` → a constrained shell/exec tool; keep it behind a trust gate.
2. **A ~2-sentence persona + a deduplicated, tool-conditional guideline list.** Adopt Pi's pattern of per-tool `promptGuidelines` merged and deduped, co-varying with the active toolset, instead of one giant static prompt.
3. **Hard truncation everywhere with actionable continuation notices.** Copy the `[Showing lines X–Y of N. Use offset=Z to continue.]` convention and the 2000-line/50KB caps verbatim; it keeps context bounded without a summarizer.
4. **Simple auto-compaction at `contextWindow − reserveTokens`, keeping a recent token budget**, triggered mid-run right before the next assistant call. Pi's `firstKeptEntryId` + "summary + recent messages" rebuild is a clean, implementable model.
5. **JSONL append-only session files keyed by `id`/`parentId`.** For a messaging UI this is ideal: a thread is a path through the tree; branching is free. Adopt v3-ish entry types (`message`, `model_change`, `compaction`, `session_info`).
6. **Defer the big prompt blocks.** Put tool/skill/project context after the static core and only include what's active — Pi hides any tool lacking a snippet.
7. **Provider conditioning, not provider lock-in.** If PocketHarness talks to arbitrary OpenAI-compatible endpoints, budget for a `compat`-style flag layer (`supportsDeveloperRole`, `maxTokensField`, `thinkingFormat`), because that is where real servers break.
8. **`constrainedSampling: json_schema/strict` with graceful fallback** — request strict tool schemas when the endpoint supports them.

**NOT worth copying for an Android app:**
- **The full extension/event-bus surface (~33 events), pi packages, themes, TUI components, OAuth providers, `/share` to GitHub gists, session HTML export, branch summarization.** This is desktop-CLI weight; a phone harness should stay tiny.
- **`grep`/`find` as separate tools that shell out to ripgrep/fd and auto-download binaries.** On Android these are fragile (no package manager, arch/ABI issues). Fold search into the exec tool or implement natively.
- **The `powershell` tool** — irrelevant on device.
- **Cost/cache-breakpoint accounting and per-turn dollar-waste audits** — valuable for a paid desktop tool, premature for MVP.
- **The tree-branching UX depth (`/tree`, labels, `/fork`, `/clone`)** — a thread-per-session messaging list is enough; store the tree but don't surface it yet.
- **Pi's silent-autonomy stance (no permission prompts) as a default on a phone.** Pi's security doc *argues* the absence is principled, but an Android harness executing shell on-device should gate execution behind a per-project allow decision (Pi itself has `--approve`/project trust; keep that, drop the "popups are bloat" absolutism).

---

## Source list

**Local (this machine):**
- `/mnt/c/Users/vruizes/AppData/Local/pi-node/current/node_modules/@earendil-works/pi-coding-agent/package.json` (version 0.84.1, bin, deps)
- `.../dist/core/system-prompt.js`, `.../dist/core/tools/index.js`, `.../dist/core/tools/truncate.js` (constants: `DEFAULT_MAX_LINES=2000`, `DEFAULT_MAX_BYTES=50*1024`, `GREP_MAX_LINE_LENGTH=500`), `.../dist/cli/args.js:342` (`ask_question` help example)
- `/mnt/c/Users/vruizes/AppData/Local/pi-node/current/CHANGELOG.md` (`## [0.84.1] - 2026-08-07`)
- `/home/vruizes/.pi/agent/skills/` (symlinks) and `/home/vruizes/.agents/skills/browser-act/SKILL.md` (real skill frontmatter)

**Repo `github.com/earendil-works/pi` (`main`, raw URLs):**
- `packages/coding-agent/src/core/system-prompt.ts`
- `packages/coding-agent/src/core/tools/index.ts`, `read.ts`, `bash.ts`, `edit.ts`, `write.ts`, `grep.ts`, `find.ts`, `ls.ts`, `powershell.ts`
- `packages/agent/src/agent-loop.ts`, `agent.ts`, `types.ts`
- `packages/coding-agent/docs/`: `index.md`, `usage.md`, `session-format.md`, `sessions.md`, `compaction.md`, `skills.md`, `extensions.md`, `providers.md`, `models.md`, `custom-provider.md`

**Web:**
- `https://pi.dev/` and `https://pi.dev/docs/latest/usage`
- arXiv:2609.00006 — "Harness Engineering: Anatomy, Architecture, and Evolution of Coding Agents" — `https://arxiv.org/html/2609.00006v1` (§11.8 "Pi: Sub-Agents as an Extension", §14.1, §6/§10.8, Figure 3, Table 9)

## Open gaps
- No public `docs/` page found for the **agent loop internals** on pi.dev; the loop description comes from reading `packages/agent/src/*.ts` source, not official docs prose.
- `ask_question` appears only as a CLI-help example; I could not find a built-in definition — treat it as extension-provided, not core.
- The arXiv paper's version snapshot ("July 2026", Pi = 7 tools) slightly predates the installed 0.84.1 (built 2026-08-07); the paper counts 7 (excluding Windows-only `powershell`), the source lists 8 names. Both are consistent once `powershell` is recognized as platform-conditional.
- Exact **token count** of the system prompt is a `chars/4` estimate (~600 tokens); I did not run a real tokenizer against it.
