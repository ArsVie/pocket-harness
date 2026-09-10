# DeepSeek Harness — the `minimal` agent preset

Engineering brief for PocketHarness (Android, OpenAI-compatible, deepseek-v4-flash).

**Evidence provenance.** Everything marked `(verified)` was read in the local clone at
`/home/vruizes/deepseek-harness` (remote `https://github.com/deepseek-ai/deepseek-harness.git`,
clone dated 2026-08-16) or in this machine's live state under `/home/vruizes/.dsh/`. Statements the
clone could not settle are marked **UNVERIFIED**. Nothing below is reconstructed from memory or
from public docs — no external source was used.

Note on the `minimalist` grep: the repo never uses the word *minimalist*. The preset is literally
named `minimal`, and it is the user's configured default (`~/.dsh/settings.yaml`:
`agent-presets: { default: minimal }`).

---

## 1. What this repo is, its layout, and its stated design goals

DeepSeek Harness (`dsh`) is DeepSeek AI's open-source agent harness. Its own README states the
architectural thesis in one sentence (verified, `README.md:7`):

> It uses an architecture where **everything is a plugin**, and is powered by
> [Cordis](https://github.com/cordiverse/cordis) …

It is explicitly unstable (verified, `README.md:13`):

> DeepSeek Harness is currently in _developer preview_ and is iterating rapidly. **THERE WILL BE
> COMPATIBILITY-BREAKING CHANGES.**

The strongest statement of design intent is in `docs/architecture.md` (verified):

> Every part of the product is a plugin, including the model adapter, the tool registry, the
> session log, and the agent loop itself, so every part is replaceable from configuration.
>
> There is no privileged core to patch: you extend dsh by mounting a plugin beside the others, and
> registrations are effects that unwind when their plugin unloads.

**Layout** (verified, `AGENTS.md:11-55`): `vendor/` (vendored Cordis), `packages/` (50+ npm
workspaces at `packages/<group>/<pkg>/`, all named `@deepseek-ai/dsh-<name>`), `apps/cli`,
`apps/web`, `python/`, `native/`, `examples/`, `docs/`, `scripts/`. Groups that matter to us:
`core/` (session, system-prompt, tools, agent, agent-loop), `llm/`, `preset/`, `session/`,
`compaction/`, `spill/`, `context/`, `terminal/`, `fs/`, `shell/`, plus the extension groups in §9.

**Profiles and bundles** (verified, `docs/architecture.md`): a running `dsh` is a plugin tree
composed at boot from ordered layers — each bundle in the profile's listed order, then the
profile's own `cordis.patch.yml`, then `$DSH_HOME/cordis.patch.yml`, then `--patch` overlays. Two
bundles ship: `packages/bundle/base/cordis.patch.yml` (the shared core: model adapters, tools,
persistence, sandbox/approval, settings, credentials) and
`packages/bundle/web-app/cordis.patch.yml` (the browser surface). `web` and `headless` are the
shipped profiles.

Four conventions from `AGENTS.md` matter for a port (all verified):

- `AGENTS.md:107` — "**Model-visible ⟺ logged**: anything that reaches a model request must be
  reconstructable from the session log; a new model-visible input requires a session event."
- `AGENTS.md:108` — "**Plugins, not loop changes**: new behavior goes on documented extension
  points; changing `agent-loop` requires updating docs/architecture.md."
- `AGENTS.md:112` — "**No hardcoded tunables in plugins**: deployment-varying choices are validated
  `Config` fields changeable from cordis.yml."
- `AGENTS.md:106` — "**Waterfall listeners MUST call `next()`** to delegate."

---

## 2. The preset system — definition, loading, selection

A preset is a **directory holding one `agent.cordis.yml`** (verified,
`packages/preset/README.md:7`). The shipped roster lives in
`apps/cli/config/agent-presets/` — one directory per preset, and that directory listing *is* the
roster (verified, `packages/preset/README.md:16`). Four presets ship:

| dir | `preset.yml` name | `order` | nature |
|---|---|---|---|
| `standard/` | 标准模式 (standard mode) | 1 | full coding agent |
| `code/` | PTC 模式 (PTC mode) | 2 | standard, presented as Code Mode |
| `minimal/` | 极简模式 (minimal mode) | 3 | **two-tool fixed-prompt agent** |
| `cordis/` | 创造模式 (creation mode) | 4 | standard + self-modification toolset |

`preset.yml` carries **display text only** — `name`, `description`, `order`. `id` is the directory
name and `trust` comes from the discovery root, so neither is writable there (verified,
`packages/preset/agent-presets/README.md`, "Display metadata").

**Resolution order.** Discovery roots are scanned in precedence order, earlier root winning a
duplicate id; the harness-home root `<dshHome>/.agent-presets` is appended last when
`includeUserRoot` is left at its default `true`. Ids must match `[a-z0-9][a-z0-9-]*`. The shipped
root is resolved by the app, not by config (verified, `apps/cli/src/profile-boot.ts:35`):

```ts
const SHIPPED_PRESET_ROOT = fileURLToPath(new URL('../config/agent-presets/', import.meta.url))
```

and patched into the `agent-presets` row's `roots` as `{ path: SHIPPED_PRESET_ROOT, trust: 'system' }`
(`profile-boot.ts:159-167`). The roster row itself is inserted by the web-app bundle (verified,
`packages/bundle/web-app/cordis.patch.yml:420-424`):

```yaml
- insert:
    - id: agent-presets
      name: '@deepseek-ai/dsh-agent-presets'
      config:
        default: standard
```

**The default is a user setting.** The plugin registers the `agent-presets` settings namespace with
`config.default` as its composition base, so the user document layers over the deployment's
engineering default (verified, `packages/preset/agent-presets/README.md`, "The default preset is a
user setting"), with the literal example:

```yaml
agent-presets:
  default: minimal
```

That is exactly what this machine has in `~/.dsh/settings.yaml`. The value is read per resolution,
not snapshotted, so a change affects the next created session and never a running one.

**Mounting.** A preset is mounted ONCE per process under a standing scope; each session that names
it joins by scope parentage. An agent's views resolve `agent → preset → global`, nearest shadowing
farthest. A preset row that publishes a service into the root realm is rejected at mount — presets
put agent-owned services behind an `isolate` realm instead. Subagents join their parent's
composition via `composeFrom()`, never by re-mounting.

**Which preset a session runs** is re-derived, not read from the header (verified,
`packages/preset/agent-presets/src/session.ts:48-54`):

```ts
export function resolveSessionPreset(session: PresetBearingSession): string | undefined {
  for (let index = session.events.length - 1; index >= 0; index -= 1) {
    const event = session.events[index]
    if (event?.type === 'agent-preset/selected') return event.data.agentPreset
  }
  return session.header.agentPreset
}
```

A composition is installed before the agent is published and never re-read while it runs, which the
README calls out as "**KV Cache effect**: Prefix-stable for the life of an agent."

---

## 3. THE `minimal` PRESET IN FULL

**File:** `/home/vruizes/deepseek-harness/apps/cli/config/agent-presets/minimal/agent.cordis.yml`
(62 lines, 2403 bytes — verified). Companion metadata,
`minimal/preset.yml` (verified, verbatim):

```yaml
name: 极简模式
description: 仅提供持久 bash 与 str_replace_editor 的双工具编码 Agent。
order: 3
```

("Minimal mode — a two-tool coding Agent offering only persistent bash and str_replace_editor.")

### 3.1 The complete system prompt

The whole of the model's system prompt is **one sentence**, set as a `persona` row in *complete*
mode (verified, `minimal/agent.cordis.yml:8-13`, verbatim in a fenced block):

```yaml
- id: persona
  name: '@deepseek-ai/dsh-persona'
  config:
    text: You are a helpful software engineer assistant.
    complete: true
    includeRuntimeContext: false
```

Rendered to the model, the system prompt is exactly:

```
You are a helpful software engineer assistant.
```

The two flags are what make it *the whole* prompt. Per `packages/preset/persona/README.md`
(verified): `complete: true` means "assembly still resolves contexts, tools, variables, and
cooperative listeners, then the prompt registry restores this exact persona as the sole section; no
identity, tool guidance, or listener can append prompt text." `includeRuntimeContext: false` means
"a fresh agent receives no runtime-context snapshot from sandbox policy, approval policy,
delegation, or another system-prompt context provider." Token effect per the same README: "Complete
mode removes every other system-prompt token for that agent."

### 3.2 The file's own header comment (verbatim, lines 1-6)

```
# The `minimal` agent preset: a fixed-prompt, two-tool coding-agent composition.
#
# The persona is the complete system prompt, so global identity, Web orientation,
# tool guidance, and later assembly listeners cannot add prompt text. Runtime
# context snapshots are suppressed for this preset, and the model composes only
# persistent `bash` and `str_replace_editor`. Context compaction is absent.
```

### 3.3 The remaining rows

Beyond `persona`, only two groups exist — both carrying `isolate` realms because they own
agent-scoped services (verified, lines 15-62):

```yaml
- id: persistent-shell
  name: cordis:group
  group: true
  isolate:
    terminals: true
  config:
    - id: pty
      name: '@deepseek-ai/dsh-terminal'
    - id: terminal-bash
      name: '@deepseek-ai/dsh-terminal-bash'
      config:
        timeoutMs: 300000
    - id: persistent-bash
      name: '@deepseek-ai/dsh-tool-bash-persistent'
      config:
        timeoutMs: 300000
        description: |-
          Run commands in a bash shell
          * When invoking this tool, the contents of the "command" parameter does NOT need to be XML-escaped.
          * You don't have access to the internet via this tool.
          * You do have access to a mirror of common linux and python packages via apt and pip.
          * State is persistent across command calls and discussions with the user.
          * To inspect a particular line range of a file, e.g. lines 10-25, try 'sed -n 10,25p /path/to/the/file'.
          * Please avoid commands that may produce a very large amount of output.
          * Please run long lived commands in the background, e.g. 'sleep 10 &' or start a server in the background.

- id: filesystem
  name: cordis:group
  group: true
  isolate:
    fs: true
  config:
    - id: fs-local
      name: '@deepseek-ai/dsh-fs-local'
      config:
        cwd: !!js process.env.DSH_CWD ?? process.cwd()
    - id: str-replace-editor
      name: '@deepseek-ai/dsh-tool-str-replace-editor'
      config:
        maxOutputChars: 16000
```

### 3.4 Model and reasoning settings

The preset itself names **no model and no reasoning setting** — those live on the host plane:
`agent-default-model` and the `llm-pi-ai` provider registry. This machine's live settings
(verified, `~/.dsh/settings.yaml`):

```yaml
llm-pi-ai:
  providers:
    opencode-go:
      apiKeyEnv: OPENCODE_GO_API_KEY
agent-presets:
  default: minimal
agent-default-model:
  provider: opencode-go
  model: deepseek-v4-flash
  reasoningEffort: max
```

(`~/.dsh/.credentials.yaml` exists and contains exactly one key, `OPENCODE_GO_API_KEY`; its value
was not read.)

### 3.5 Per-preset flags, in one list

| flag | value | effect |
|---|---|---|
| persona `complete` | `true` | this text is the *only* system-prompt section |
| persona `includeRuntimeContext` | `false` | zero runtime-context snapshot messages |
| `persistent-shell.isolate.terminals` | `true` | PTY registry is entry-local, not process-global |
| `filesystem.isolate.fs` | `true` | bare local FS shadows the host's sandboxed provider for this preset |
| `tool-bash-persistent.timeoutMs` | `300000` | 5-minute per-command wall clock |
| `tool-str-replace-editor.maxOutputChars` | `16000` | view clipping budget |
| compaction rows | **absent** | no auto-compaction, no `/compact`, no tool-result pruning |

### 3.6 Why the model really sees only two tools

The base bundle inserts host-plane tool rows (`tool-bash`, `tool-fs`, `tool-fs-search`, `tool-jobs`,
`tool-skill`, `tool-goal`, `tool-todo`, `tool-web`, `tool-ralph`, `tool-subagent*`, `tool-workflow`,
`tool-str-replace-editor`, `plan-mode`, `compaction-basic`, `command-compact`,
`tool-result-pruner`, `agent-instructions` — verified, `packages/bundle/base/cordis.patch.yml`). The
web-app bundle **disables every one of them** so that the agent plane is owned exclusively by
presets. Verbatim from `packages/bundle/web-app/cordis.patch.yml:276-285`:

```
# ── the agent plane moves behind agent presets ─────────────────────────────
#
# Every row below composes what ONE agent contributes to the host registries:
# its tools, its prompt sections, its delegation backends. The base keeps them
# for the TUI, which is single-session and composes its agent process-wide; the
# Web surface disables them here and lets each session mount a preset instead.
#
# Disabling rather than deleting is deliberate: the base is shared, and a row
# absent from a surface overlay would silently reappear the day someone reorders
# the composition.
```

followed by ~25 `- id: <row>` / `  disabled: true` pairs through line 408. Consequently, on the web
surface, a `minimal` agent's tool set is **exactly** the two tools its own composition registers.
This is the single most portable idea in the whole system.

---

## 4. Tool surface

**Total: 2 model-facing tools.** (`docs/tool-catalog.md`, generated by booting each tool plugin and
harvesting `ctx.tools.schemas()`, documents 52 entries across all shipped tool packages — `grep -c
'^### ' docs/tool-catalog.md` = 52. The `minimal` preset exposes two of them.)

| # | name | plugin | parameters (exact) | required |
|---|---|---|---|---|
| 1 | `bash` | `@deepseek-ai/dsh-tool-bash-persistent` | `command: string` | `command` |
| 2 | `str_replace_editor` | `@deepseek-ai/dsh-tool-str-replace-editor` | `command: string (enum view\|create\|str_replace\|insert)`, `path: string`, `file_text: string`, `insert_line: integer`, `new_str: string`, `old_str: string`, `view_range: integer[]` | `command`, `path` |

**Tool 1 — `bash`.** Exact JSON schema (verified, `docs/tool-catalog.md:510-523`):

```json
{
  "type": "object",
  "properties": {
    "command": {
      "type": "string",
      "description": "The bash command to run. Relative path is preferred in the command."
    }
  },
  "required": ["command"]
}
```

Description as the minimal preset actually ships it: the 7-line bulleted block quoted in §3.3
(registered at `packages/shell/tool-bash-persistent/src/index.ts:374-398`; the plugin's own default
when `description` is unset is, verbatim, `'Run commands in a persistent bash shell. State, including
the current directory and exported environment variables, persists across calls for this agent.'`,
`src/index.ts:25`). The return value is a plain string; `output.schema = { type: 'string' }` with
`render: (_args, value) => [{ type: 'text', text: value }]` (`src/index.ts:384-387`). A non-zero
exit appends `[exit code: N]` (`src/index.ts:176-179`); output over `maxOutputChars` (default
16000) is clipped with a fixed `<response clipped><NOTE>…</NOTE>` tail (`src/index.ts:15,55-60`).

**Tool 2 — `str_replace_editor`.** Exact JSON schema (verified, `docs/tool-catalog.md:546-593`):

```json
{
  "type": "object",
  "properties": {
    "command": {
      "type": "string",
      "description": "The commands to run. Allowed options are: `view`, `create`, `str_replace`, `insert`.",
      "enum": ["view", "create", "str_replace", "insert"]
    },
    "path": {
      "type": "string",
      "description": "Absolute path to file or directory, e.g. `/repo/file.py` or `/repo`."
    },
    "file_text": {
      "type": "string",
      "description": "Required parameter of `create` command, with the content of the file to be created."
    },
    "insert_line": {
      "type": "integer",
      "description": "Required parameter of `insert` command. The `new_str` will be inserted AFTER the line `insert_line` of `path`."
    },
    "new_str": {
      "type": "string",
      "description": "Optional parameter of `str_replace` command containing the new string (if not given, no string will be added). Required parameter of `insert` command containing the string to insert."
    },
    "old_str": {
      "type": "string",
      "description": "Required parameter of `str_replace` command containing the string in `path` to replace."
    },
    "view_range": {
      "type": "array",
      "description": "Optional parameter of `view` command when `path` points to a file. If none is given, the full file is shown. If provided, the file will be shown in the indicated line number range, e.g. [11, 12] will show lines 11 and 12. Indexing at 1 to start. Setting `[start_line, -1]` shows all lines from `start_line` to the end of the file.",
      "items": { "type": "integer" }
    }
  },
  "required": ["command", "path"]
}
```

Behaviour worth porting (verified in `packages/fs/tool-str-replace-editor/src/index.ts`):
`path` **must be absolute** or the call fails with a teaching error, "The path X is not an absolute
path, it should start with `/`. Maybe you meant /X?" (`:94-96`); `view` on a file renders `cat -n`
with 6-wide right-aligned line numbers (`:179-182`); `view` on a directory lists up to 2 levels
deep, excluding dotfiles, `node_modules`, and `__pycache__` (`:194-213`); `create` refuses an
existing file (`:249-251`); `str_replace` fails if `old_str` is absent (`:294-299`) or non-unique,
naming the offending lines (`:300-306`); writes are versioned with `replaceIfVersion` so a stale
read cannot clobber (`:309-317`). Errors are ordinary thrown `FsError`s carrying stable codes
(`FS_NOT_FOUND`, `FS_EDIT_NOT_FOUND`, `FS_AMBIGUOUS_EDIT`, `FS_SANDBOX_DENIED`).

**What the non-minimal presets add, and why.** `standard` (verified,
`apps/cli/config/agent-presets/standard/agent.cordis.yml`) adds, in its own words: `agent-instructions`
(AGENTS.md/CLAUDE.md chains), `tool-bash` + `tool-pwsh` (platform-gated), `tool-fs` + `tool-fs-search`
(there is no `read`/`edit`/`glob`/`grep` tool when `fs-local` + the editor are the whole file story),
`tool-jobs` (background job controls), `skill-filesystem` + `tool-skill`, `tool-goal`, `plan-mode`,
`compaction-basic` + `command-compact` + `tool-result-pruner`, `tool-subagent` +
`tool-subagent-fork` + `tool-subagent-control` + `tool-subagent-list-agents`, `tool-workflow`, plus
`tool-todo`, `tool-web`, `ask_user_question`, `ralph` from other rows. The `code` preset is
`standard` unchanged but with a `tool-presentation` row so the model writes one TypeScript program
against a generated SDK instead of one tool call per action (its `preset.yml`: "具备标准模式的全部
能力，并通过 Code Mode SDK 呈现工具" — all of standard's capability, tools presented through the Code
Mode SDK). The design intent is stated in each file's header comment.

The minimal rationale is visible in the *omissions*: no filesystem search tools (bash is the
searcher — `grep -n`, `sed -n`), no `read` tool (the editor's `view` covers it), no skill catalog,
no delegation, no plan mode, no todo, no web. Everything the model can do it does through a shell
and a string-replace editor — the two primitives that are *composable without a schema*.

---

## 5. The agent loop

The concrete loop is `ReactLoopAgent`, `packages/core/agent-loop/src/agent.ts:64`; everything else
(compaction, subagents, retry, guards) listens on the event taxonomy rather than editing the loop.

**Turn cycle** (verified). `kick()` is `while (await this.turn()) {}` (`agent.ts:210-212`). Each
turn opens with `session.append('turn/start', {...})` (`agent.ts:255`) then loops over steps:
`preStep` claims pending inbox messages (`agent.ts:229`), assembles the system prompt
(`agent.ts:230`), projects a runtime-context snapshot as an extra user-role message, and runs the
`agent/pre-step` waterfall (`agent.ts:234`); a `reject` closes the turn as `blocked`
(`agent.ts:267-269`). Each entered step appends `step/start` (`agent.ts:278-279`) and one
`user/message` per claimed message (`agent.ts:281-283`). The request is built from
`session.deriveMessages()` (`agent.ts:341`), which folds **only** the three surface-eligible event
types `user/message`, `assistant/message`, `tool/result` (`packages/core/session/src/surface.ts:15-19`).

**Streaming** (verified). Always on. The protocol is `StreamChunk`
(`packages/llm/llm/src/types.ts:291-303`): `block-start`, `text-delta`, `reasoning-delta`,
`tool-call-delta`, `block-end`, `usage`, terminal `finish { reason, replayState? }`. Every chunk is
appended durably as `assistant/chunk` (`agent.ts:349`) and pushed into a `BlockAssembler`; the
assembled result becomes one `assistant/message` anchor citing the chunk seqs (`agent.ts:381-390`).
The DeepSeek adapter always sends `stream: true` and `stream_options: { include_usage: true }`
(`packages/llm/llm-deepseek/src/serialize.ts:176-177`).

**Tool-call handling** (verified). `executeToolCalls` (`tool-calls.ts:59`) groups calls by live
concurrency mode: only an exact `true` from `isConcurrencySafe` yields `parallel`; unknown, hidden,
or throwing tools are `exclusive` and act as ordering barriers (`packages/core/tools/src/index.ts:1276-1285`).
Parallel-safe siblings run in a bounded rolling pool, `DEFAULT_MAX_PARALLEL_TOOL_CALLS = 10`
(`packages/core/agent-loop/src/constants.ts:6`), but results commit in **model order**
(`tool-calls.ts:146-160`). Any number of calls may appear in one assistant message. Failures never
throw out of the loop: a tool body error becomes `Error: <message>` with `isError: true`
(`packages/core/tools/src/index.ts:1870-1878`); an unknown name becomes `UNKNOWN_TOOL`
(`index.ts:494-510`); a guard denial becomes an `isError` text result (`index.ts:1486-1498`); an
aborted unstarted call gets a synthetic `ABORTED_BEFORE_DISPATCH` result
(`tool-calls.ts:240,249-259`).

**Stopping** (verified). A step ends `completed`, `null` (continue the turn after tool calls), or
sticky `max-tokens`. A turn ends with `TurnEndReason` ∈ `completed | aborted | blocked | error |
max-tokens | interrupted` (`packages/core/session/src/types.ts:155-174`). The driver keeps running
turns only while `inbox.hasPending`; otherwise `turn()` returns false and `kick()` exits
(`agent.ts:324-330`). **There is no iteration cap** — `packages/core/agent-loop/README.md:134`
states verbatim: "No built-in turn budget — tool calls or steering continue the current turn; a
policy that bounds runaway turns must cancel from an existing lifecycle extension point such as
`agent/turn-stopping`." No `maxSteps`/`max_steps` constant exists anywhere under `packages/`
(verified by grep).

**Steering** (verified). In-loop injection is first-class: `followup` → next turn, `steer` → next
step with wakeup, `inject` → next step without (`agent.ts:122-132`). `cancel()` clears the inbox
unless `keepInbox` and aborts the turn controller (`agent.ts:134-140`).

**Reasoning / thinking.** `reasoningEffort` is an opaque adapter-owned id on `LlmCallConfig`
(`packages/llm/llm/src/call-config.ts:23-30`) and on `GenerateOptions` (`llm/src/types.ts:325`);
`ctx.llm.prepareCall()` validates it against the model's advertised efforts and records which
fields came from the adapter (`llm/src/index.ts:786-793`), throwing
`UNSUPPORTED_REASONING_EFFORT` before any I/O (`index.ts:743-764`). On the wire the DeepSeek adapter
emits two **top-level** body fields (verified, `serialize.ts:178-181`):

```
thinking: { type: 'enabled' | 'disabled' }
reasoning_effort: 'high' | 'max'
```

`high`/`max` map to thinking enabled plus the effort; `off` maps to thinking disabled and never
crosses as `reasoning_effort`. Inbound `delta.reasoning_content` becomes a `reasoning-delta`
(`translate.ts:132-140`), is persisted in the log (packed into `reasoning-chunks` rows,
`packages/core/session/src/chunk-rows.ts:6,66`), assembles into a `{ type: 'reasoning', text }`
block, and is surfaced in the conversation UI
(`packages/client/ui-conversation/src/client/conversation-nodes/assistant.ts:93-95`). Crucially it
is replayed to the model **only on turns that carried tool calls** (`serialize.ts:96-99`):

```ts
...toolCalls.length > 0 && reasoning.length > 0 ? { reasoning_content: reasoning } : {},
```

---

## 6. Session & transcript format

**On-disk layout** (verified). Root is `dshHomePath('sessions')` = `$DSH_HOME/sessions`, default
`~/.dsh/sessions` (`packages/bundle/base/cordis.patch.yml:98-101`;
`packages/util/home-paths/src/index.ts`). One readable project directory per cwd, one session
directory per session, one fixed filename:

```
<root>/--<normalized-cwd>--/<encoded-session-id>/session.jsonl.zstd
```

The directory name is derived, not stored — `projectKey` replaces separators with `-`, escapes
unsafe code units, and wraps the slug (verified,
`packages/session/session-persistence-jsonl/src/format.ts:165-166`):

```ts
const slug = readable.replace(/^-+/, '') || 'root'
return `--${slug.slice(0, 251)}--`
```

This machine's real layout matches exactly:
`/home/vruizes/.dsh/sessions/--home-vruizes-projects--/session-8df5f30b-2d7a-488b-a656-bc2bd6ceb56d/session.jsonl.zstd`
(88,242 bytes, Zstandard magic `28b52ffd…`).

**Serialisation** (verified). A standard concatenation of independent checksummed Zstandard frames:
one frame holding only the header line, then one frame per durable append batch
(`format.ts:619-626`, `zstd.ts:111`). Logically it is line-delimited JSON: line 1 is the header,
every later line one storage record. With `packChunks` on (default) runs of ≥3 consecutive
`assistant/chunk` deltas pack into `text-chunks` / `reasoning-chunks` / `tool-call-chunks` rows
(~60% smaller logical logs); readers are layout-blind. The envelope is
`{ type, seq, time, data }` plus optional `ignorable: true` and, on the three surface types only,
`sourceEventSeqs` / `surfaceOp` (`packages/core/session/src/types.ts`). `seq` is the monotonic log
index and stays contiguous (`events[i].seq === i`).

**Header — real, live, from this machine** (verified; decompressed from the transcript above):

```json
{"type":"session","version":0,"id":"session-8df5f30b-2d7a-488b-a656-bc2bd6ceb56d","createdAt":1786862930445,"cwd":"/home/vruizes/projects","delegationDepth":0,"agentPreset":"minimal"}
```

That `"agentPreset":"minimal"` is direct proof the live session ran on the minimal preset. The
header is tagged `type: 'session'` so readers can tell it from events; fields are `version`, `id`,
`createdAt`, optional `cwd`/`parentSession`/`seedLength`/`origin`/`agentPreset`, and required
`delegationDepth` (`format.ts:33-44,51-64`). `SESSION_FORMAT_VERSION` is pinned at `0` with no
compatibility promise (§1; `packages/core/session/src/types.ts:56`), and a mismatched version is
refused, never migrated.

**Event vocabulary** (verified). Core members (`packages/core/session/src/types.ts`): `turn/start`,
`turn/end`, `step/start`, `step/end`, `user/message`, `assistant/chunk`, `assistant/message`,
`tool/call`, `tool/result`, `todo/write`, `request/header`, `request/context`, `session/end-seed`.
Merge-extended families include `agent/inbox/spliced`, `agent-preset/selected`, `session/title`,
`tool/code-dispatch*`, `compaction/*`, `hook/*`, `approval/*`, `plan/mode`, `sandbox/mode`,
`permission/preset`. The generated `docs/persistence-catalog.md` is the authoritative enumeration.
Code-computed counts from committed fixtures: `examples/acp-agent/tests/snapshots/advanced-toolchain/session.jsonl`
= 76 logical lines (1 header + 75 events), of which `assistant/chunk` 30, `step/start` 6,
`assistant/message` 6, `tool/call` 5, `tool/result` 5.

**A tool call on disk** carries the name plus the **raw unparsed** arguments JSON string and a
`callId`; the result is a `tool/result` surface event citing the call's seq via `sourceEventSeqs`
(verified, redacted fixture line):

```json
{"type":"tool/call","seq":15,"time":1785730458440,"data":{"turn":1,"step":1,"callId":"advanced-define","name":"cordis_define","arguments":"{\"plugin\":{\"kind\":\"new\",\"idPrefix\":\"snap\"},…}"}}
```

**Resumption** (verified). `ctx.agents.resume({ resumeSessionId })` → `resumeWith` →
`persistence.prepare(id)` (`packages/core/agent-loop/src/index.ts:653-685`). Replayed history is
demarcated by the log-only `session/end-seed` event (last one wins) and `session.firstLiveSeq`.
Crash recovery never truncates a complete open turn — it closes it with a synthetic
`turn/end { reason: { kind: 'interrupted' } }` — and only a torn final *frame* is truncated and
re-encoded with synthetic closers. Forking is an inclusive event-seq prefix that must end outside an
open turn, recording `parentSession`/`seedLength`. The preset for a resumed session is re-derived
via `resolveSessionPreset` (§2), not read from the header alone.

**Non-log state** (verified, live). `~/.dsh/storages/workspace.json` is a domain-KV document
(unit `workspace` v2) mapping workspace ids to `{ path, title, sessionIds, createdAt, updatedAt }`.
`~/.dsh/storages/session_projcache.json` (unit `session_projcache` v3) is a **persisted projection
cache**: `tables.sessions[<id>] = { identity, rows }` with rows `sessionStats`, `title`, `goal`,
`tokenUsage`, `contextPressure`, `contextBreakdown`, `subagent`, `permissions`,
`sessionListMetadata`, `imageLimits`. Real live values for the minimal session:
`sessionStats { turns: 3, steps: 17, seq: 3001, llmMs: 43782, toolMs: 94781 }`;
`tokenUsage.totals { uncachedInputTokens: 16127, outputTokens: 3286, cacheReadTokens: 138368,
cacheWriteTokens: 0 }`. The cache-read number is the payoff of the stable-prefix design.

---

## 7. Context management

**Compaction family** (verified). `compaction-basic` is the default backend: `thresholdRatio` 0.8,
`retainRatio` 0.16, `maxTokens` 8192, `compactionRetries` 1, `maxOverflowRetries` 1, `auto` true; it
measures with `ctx.tokenMeter`, snapshots the canonical envelope, and summarizes with a one-shot
`ctx.llm.stream()` that replays the conversation prefix so the provider's warm cache is reused. The
model-free `compaction-tool-result-pruner` has exactly three keys — `thresholdChars` 8192,
`headChars` 4096, `tailChars` 1024 — restated verbatim in `standard/agent.cordis.yml:150-155`.
`command-compact` registers an argument-free `/compact`.

**The minimal preset has none of it.** Its header says so literally: "Context compaction is absent."
With the backend absent, no step-boundary pressure compaction runs, no overflow recovery runs,
`/compact` is not registered, and oversized tool results are never pruned — history grows until the
provider itself rejects the request. This is a deliberate minimal-mode choice, but note the caveat:
the base host composition mounts `compaction-basic`, `command-compact`, and `tool-result-pruner`
unconditionally (`packages/bundle/base/cordis.patch.yml:284-290,360-365`). **UNVERIFIED:** whether a
preset's agent-plane roster suppresses those host-plane rows too, or whether the header's phrase
describes only the preset's own file. The web-app patch does disable all three by id
(`web-app/cordis.patch.yml:358-365`), which makes "absent" true on the web surface and leaves the
TUI case open.

**Spill** (verified). `spill-policy` is a `tools/post-execute` transformer configured with
`maxInlineBytes: 50000`; an oversized plain-text result is replaced by a bounded head/tail preview
plus a locator produced by `spill-local` (session-scoped files, `<root>/session-<hash>/…`,
`0o600`). It is best-effort and skips `read`, nested executions, and non-text results.

**Context providers** (verified). `packages/context/` contributes model-visible request context
without defining tools: `agent-instructions` (AGENTS.md/CLAUDE.md chains, the default),
`time-context`, `tmux-context`, `session-reference` (opt-in). The minimal preset's
`includeRuntimeContext: false` suppresses *all* of them for its agents, including changes made by
assembly listeners.

**Prefix caching** is treated as a first-class design constraint throughout. Representative verbatim
statements (all verified): `packages/core/system-prompt/README.md:69` — "Prefix-stable while
identity, persona, variables, section text, and order render identically. Any change may invalidate
reuse from the first changed system-prompt token."; `packages/core/agent/README.md:111` —
"Prefix-stable while an agent's scoped registrations are unchanged."; plan mode's own section text —
"The tool catalog stays the same across modes for request-cache stability"; and
`packages/preset/agent-presets/README.md` — "Prefix-stable for the life of an agent: a composition
is installed once, before the agent is published and therefore before its first request, and is never
re-read while the agent runs."

---

## 8. Provider layer

**Registry** (verified). `ctx.llm` in `packages/llm/llm/`; `ctx.llm.registerAdapter(providers,
adapter)` registers one adapter for a set of routes. Two adapters ship: `dsh-llm-deepseek` (owns the
`deepseek-official` route and builds the HTTP body itself) and `dsh-llm-pi-ai` (multi-provider,
delegates body construction to the external pi-ai library), mounted dormant until an `llm-pi-ai:`
settings section supplies provider profiles.

**Config shape.** The pi-ai adapter registers settings namespace `llm-pi-ai`
(`packages/llm/llm-pi-ai/src/index.ts:87`). Schema (`src/config.ts:171-179`):

```ts
export interface Config {
  providers?: Record<string, PiAiProviderProfile>
}
```

The providers dict key **is** the provider route. The supported profile fields are `apiKeyEnv`,
`displayName`, `api`, `baseURL`, `models`, `modelOverrides`, `compat`, `defaultContextWindow`,
`defaultMaxTokens`, `defaultInput`, `headers`, `reasoning`, `thinkingBudgets`, `cacheRetention`,
`transport`, `timeoutMs`, `websocketConnectTimeoutMs`, `streamIdleTimeoutMs`, `retryPolicy`
(`packages/llm/llm-pi-ai/README.md:116`; interface `src/config.ts:65-141`). Two of these are the
ones to copy: `apiKeyEnv` is "a credential *reference* resolved per request, so no secret enters
this file", and `baseURL` "sets the endpoint of every model on the route". A configured reference
that resolves to nothing fails the request with `MISSING_CREDENTIAL` — fail loud, never silently
unauthenticated.

A minimal OpenAI-compatible route, derived from the real schema and README:

```yaml
llm-pi-ai:
  providers:
    my-gateway:
      api: openai-completions
      baseURL: https://gateway.example/v1
      apiKeyEnv: MY_GATEWAY_API_KEY
      models:
        - id: deepseek-v4-flash
          contextWindow: 65536
          maxTokens: 8192
          reasoningEfforts:
            off:
            high: high
            max: ultra
```

Per-model `reasoningEfforts` maps a level selector to the spelling dispatch sends on the wire.

**Wire shape.** The native adapter's request interface (`packages/llm/llm-deepseek/src/types.ts:12-30`),
verbatim:

```ts
export interface WireRequest {
  model: string
  messages: WireMessage[]
  stream: true
  stream_options: { include_usage: true }
  thinking?: { type: 'enabled' | 'disabled' }
  reasoning_effort?: 'high' | 'max'
  tools?: WireTool[]
  temperature?: number
  max_tokens?: number
  stop?: string[]
}
```

The harness-side call vocabulary is exactly `provider`, `model`, `reasoningEffort`, `temperature`,
`maxTokens`, `stop` (`packages/llm/llm/src/call-config.ts:23-30`) — no `tool_choice`, no `top_p`, no
penalties. Compaction calls are marked with the header `x-deepseek-harness-compact: 1` without
touching the model-visible body.

**UNVERIFIED:** whether pi-ai's installed catalog ships an `opencode-go` route (inheriting an
endpoint) or whether it must be hand-declared with `api`/`baseURL`/`models`; and the exact HTTP body
pi-ai builds for OpenAI-compatible routes, since that construction lives in the external dependency.
The `WireRequest` shape above is the harness's own and is what the OpenAI-compatible contract should
be modelled on.

---

## 9. Extension surface

All verified from the respective package READMEs. The minimal preset includes **none** of the
model-facing ones.

- **`packages/skill/`** — reusable instruction files behind a provider-neutral catalog. `tool-skill`
  is a model-facing tool *and* contributes a durable `<system-reminder>` catalog **prompt section**;
  `skill` / `skill-filesystem` are providers.
- **`packages/subagent/`** — delegation to child agents. `subagent` is the provider registry seam
  (`ctx.subagents`, a process singleton); `tool-subagent`, `tool-subagent-control`,
  `tool-subagent-list-agents`, `tool-subagent-fork` are model-facing tools. Children join the
  parent's composition via `composeFrom()`, never by re-mounting.
- **`packages/hooks/`** — runs external shell hooks on typed interception points. Neither a tool nor
  a prompt section by itself; can inject source-attributed context. Configured by a `configPath`,
  parsed once at load.
- **`packages/mcp/`** — connects external MCP servers and registers their tools as model-facing
  tools named `mcp__<serverName>__<rawName>`; one plugin instance per server in `cordis.yml`.
- **`packages/acp/`** — an automation-only Agent Client Protocol server over JSON-RPC stdio: a
  transport adapter, neither tool nor prompt section.
- **`packages/extensions/`** — the agent modifies its own runtime. `tool-cordis` exposes five
  model-facing tools (`cordis_inspect*`, `cordis_define`, `cordis_run`, `cordis_stop`,
  `cordis_undefine`) and evaluates model-written JavaScript against the live runtime; shipped only in
  the `cordis` preset, with the explicit warning that such a session should be treated as shell
  access.

The minimal preset's complete row list is `persona`, `pty`/`terminal-bash`/`persistent-bash`, and
`fs-local`/`str-replace-editor` — no skill, no subagent, no hook bridge, no MCP client, no ACP
server, no `tool-cordis`.

---

## 10. Lessons for PocketHarness

1. **A preset can be a single file.** `minimal` is 62 lines of YAML and produces a complete agent.
   Port the *shape*: an Android `assets/presets/minimal.yaml` with `persona`, `tools[]`, and
   `model`, loaded at session creation. Do not build a plugin runtime to get one preset.
2. **The prompt can be one sentence.** The entire minimal system prompt is `You are a helpful
   software engineer assistant.` Everything else lives in tool descriptions. If PocketHarness needs
   a longer system prompt for a mode, that is a signal the tools are under-described, not that the
   prompt is too short. Also port `complete: true` semantically: a mode that owns the prompt must
   be able to *suppress* identity, runtime-context, and listener additions, or the "minimal" claim
   is cosmetic.
3. **Two tools is a legitimate product decision.** `bash` + `str_replace_editor` cover
   read/grep/glob/edit/create/run through *arguments of an existing schema* rather than new
   schemas. This is the cheapest lever on our tool-schema token budget and on model accuracy.
4. **Suppress the global/default tool plane explicitly.** The web-app bundle disables ~25 host rows
   by id rather than deleting them, with the stated reason that an absent row "would silently
   reappear the day someone reorders the composition." On Android: make the default tool set
   *skipped*, not *absent*, and keep the ids so a future reorder cannot resurrect them.
5. **No iteration cap, but a steering primitive.** The loop is `while (await turn())` with no step
   budget; runaway turns are a *policy* concern exposed through `agent/turn-stopping`, and the
   user-steering trio (`followup` / `steer` / `inject`) is first-class. For a mobile client, the
   equivalent decisions are: an explicit Stop button that aborts the turn controller, and a message
   queue that can steer mid-turn rather than only starting a new turn. Add our own cap as a policy
   layer, not inside the loop.
6. **Every tool failure is a model-visible result, never an exception.** Tool body errors become
   `Error: <message>` with `isError: true`; unknown tools become `UNKNOWN_TOOL`; aborted calls get a
   synthetic pair so every `tool/call` has a matching `tool/result`. Model the same in Kotlin: a
   sealed `ToolOutcome { Ok(text), Err(code, message) }` rendered uniformly.
7. **One append-only, seq-indexed event log per session, with a header that decides the
   composition.** The header's `agentPreset` is durable *because* it decides tools and prompt;
   resumption re-derives the preset from the last `agent-preset/selected` event rather than trusting
   the header. PocketHarness should do exactly this: header (id, createdAt, cwd, preset) + ordered
   events, one line each. Skip Zstandard framing and packed chunk rows — those exist for a
   50k-line desktop transcript; JSON-lines + SQLite (or a single JSONL file per session) is right
   for a phone.
8. **A projection cache is worth it; a second source of truth is not.** `session_projcache.json`
   holds only *derived* values (`sessionStats`, `title`, `tokenUsage`, `contextPressure`,
   `permissions`) keyed by `{ ver, seq }` so it can be rebuilt from the log. Copy the pattern,
   not the file: Android needs fast list rendering (message thread = messaging app), so cache
   derived per-session summaries in Room, keyed by log seq.
9. **Stable prefix buys real money.** The live minimal session read 138,368 cached input tokens
   against 16,127 uncached. Practical rules: install the tool catalog and system prompt once per
   session and never re-render them per turn; keep the tool list byte-identical across modes ("The
   tool catalog stays the same across modes for request-cache stability"); append-only history with
   no reordering; reasoning text persisted but **not** replayed except on tool-call turns.
10. **Provider config is a route table with env-var credential references.** Copy the
    `{ api, baseURL, apiKeyEnv, models[] }` shape. `apiKeyEnv` should be an *indirection* on
    Android too (store the key in EncryptedSharedPreferences / Keystore and name it), never inline
    secrets in a config file — and fail loud with `MISSING_CREDENTIAL` rather than sending an
    unauthenticated request.
11. **`reasoningEffort` is an opaque level id, not a boolean.** Validate it against the model's
    advertised levels before sending and fail fast with an `UNSUPPORTED_REASONING_EFFORT`-style
    error. The wire spelling for our target is top-level `thinking: {type}` +
    `reasoning_effort`; treat the mapping as data.

**What does not transfer to Android/mobile:**

- **The Cordis plugin/realm/scope architecture.** Preset realms, standing mounts, `isolate`
  realms, and scope-parentage joins solve *many concurrent differently-composed agents in one
  process*. A phone runs one session at a time. Port the *concept* (a preset is a composition of
  persona + tools + model) into a plain Kotlin data class; do not port a DI container.
- **PTY-backed persistent bash.** Android has no such shell in a sandboxed app. The nearest
  equivalents are Termux-style userland (not shippable on Play), a POSIX shell in a bundled
  proot/`libtermux`, or — realistically — replacing `bash` with a *virtual* exec tool (file ops +
  a fixed command vocabulary) and keeping the two-tool philosophy by pairing it with
  `str_replace_editor`. Note the preset's own tool description ("You don't have access to the
  internet via this tool… a mirror of common linux and python packages via apt and pip") is written
  for a container and must be rewritten per device.
- **A 300-second per-command timeout and 16k-char clipping** assume a desktop-class context window.
  On a phone, shorter timeouts and smaller clip budgets are the right defaults; keep them as
  per-preset config fields so they stay tunable.
- **The compaction/spill subsystem.** Removing auto-compaction is fine for short mobile sessions
  (and is what minimal does), but a phone *will* hit context limits in long sessions; ship the
  equivalent of the tool-result pruner's three numbers (`thresholdChars` / `headChars` / `tailChars`)
  as a cheap, model-free fallback rather than the full summarization backend.
- **Subagents, workflows, MCP, ACP, hooks, skills, plan mode.** All of these are model-facing
  *tools* and prompt sections that add schemas. Under a minimalist philosophy for
  deepseek-v4-flash, leave them out of the minimal preset entirely — which is exactly what the
  reference does.
- **Zstandard frame concatenation with per-batch checksums and torn-tail repair.** Genuinely
  valuable engineering, genuinely unnecessary on mobile; plain JSONL is enough, with the log's
  append-only + contiguous-`seq` invariant kept as the integrity check.

---

## Sources

All paths relative to `/home/vruizes/deepseek-harness` unless absolute.

**Repo docs on disk**
- `README.md` (repo purpose, developer-preview warning)
- `AGENTS.md:11-55` (layout), `:104-120` (conventions: model-visible ⟺ logged, plugins-not-loop-changes, no hardcoded tunables, waterfall `next()`)
- `docs/architecture.md` (Cordis, everything-is-a-plugin, no privileged core, profiles & bundles, layer order)
- `docs/tool-catalog.md` (generated; §502-597 = the `bash` and `str_replace_editor` schemas; 52 `###` entries)
- `docs/persistence-catalog.md` (generated event enumeration)
- `docs/subsystems/session.md`, `docs/subsystems/persistence.md`, `docs/subsystems/storage.md`, `docs/subsystems/session-projection.md`
- `docs/subsystems/session-telemetry.md`
- `packages/README.md`, `packages/*/README.md` (per-package contracts)

**The preset itself**
- `apps/cli/config/agent-presets/minimal/agent.cordis.yml` (62 lines, 2403 bytes) — **the minimal preset**
- `apps/cli/config/agent-presets/minimal/preset.yml`
- `apps/cli/config/agent-presets/{standard,code,cordis}/{agent.cordis.yml,preset.yml}` — comparison
- `apps/cli/src/profile-boot.ts:35,142-171` — shipped preset root, `composeProfile`

**Preset machinery**
- `packages/preset/README.md`, `packages/preset/agent-presets/README.md`, `packages/preset/persona/README.md`
- `packages/preset/agent-presets/src/session.ts:48-54` — `resolveSessionPreset`

**Composition**
- `packages/bundle/base/cordis.patch.yml` (host plane; tool rows, compaction rows, llm rows)
- `packages/bundle/web-app/cordis.patch.yml:276-424` — agent plane moves behind presets; all host tool rows disabled; roster row

**Tools**
- `packages/shell/tool-bash-persistent/src/index.ts` (persistent `bash`)
- `packages/fs/tool-str-replace-editor/src/index.ts` (`str_replace_editor`)

**Loop, session, context, provider**
- `packages/core/agent-loop/{README.md,src/agent.ts,src/tool-calls.ts,src/constants.ts,src/index.ts}`
- `packages/core/session/{README.md,src/types.ts,src/surface.ts,src/chunk-rows.ts}`
- `packages/core/tools/src/index.ts`, `packages/core/tools/README.md`
- `packages/core/system-prompt/{README.md,src/index.ts}`, `packages/core/agent/README.md`
- `packages/llm/llm/src/{types.ts,call-config.ts,index.ts,assembler.ts}`, `packages/llm/llm/README.md`
- `packages/llm/llm-deepseek/src/{serialize.ts,translate.ts,types.ts,adapter.ts}`, `packages/llm/llm-deepseek/README.md`
- `packages/llm/llm-pi-ai/src/{config.ts,index.ts}`, `packages/llm/llm-pi-ai/README.md`
- `packages/session/session-persistence-jsonl/{README.md,src/format.ts,src/zstd.ts}`
- `packages/session/session-projection-cache/README.md`, `packages/session-query/README.md`
- `packages/compaction/*/README.md`, `packages/spill/*/README.md`, `packages/context/*/README.md`
- `packages/skill/README.md`, `packages/subagent/README.md`, `packages/hooks/README.md`, `packages/mcp/README.md`, `packages/acp/README.md`, `packages/extensions/README.md`
- `packages/util/home-paths/src/index.ts`
- `.agents/notes/implemented/architecture/2026-07-19-zstandard-jsonl-session-logs.md`
- `.agents/notes/implemented/architecture/2026-08-10-host-plane-ownership-after-presets.md`

**Committed fixture transcripts (counts computed, not eyeballed)**
- `examples/acp-agent/tests/snapshots/advanced-toolchain/session.jsonl`
- `apps/web/tests/snapshots/code-mode-round/session.jsonl`

**Live machine state** (`/home/vruizes/.dsh/`, this machine)
- `settings.yaml` — `agent-presets.default = minimal`; `agent-default-model = opencode-go / deepseek-v4-flash / reasoningEffort max`; `llm-pi-ai.providers.opencode-go.apiKeyEnv = OPENCODE_GO_API_KEY`
- `.credentials.yaml` — one key present (`OPENCODE_GO_API_KEY`); value not read
- `sessions/--home-vruizes-projects--/session-8df5f30b-2d7a-488b-a656-bc2bd6ceb56d/session.jsonl.zstd` — 88,242 bytes; header line decompressed and quoted in §6
- `storages/workspace.json`, `storages/session_projcache.json`

**Not used:** no public documentation, blog post, or website was consulted; every claim is from the
clone or from live local state.
