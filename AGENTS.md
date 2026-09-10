# AGENTS.md — PocketHarness

Android coding harness. Read `planning/SPEC.md` before writing code; it is the frozen contract.
Decisions live in `planning/decisions/` (ADRs) and are not re-litigated in code.

Build: this is a Gradle project on the Linux filesystem, built from WSL.

```
export ANDROID_HOME=$HOME/android-sdk JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
./gradlew :core:test :app:assembleDebug
```

Device work (the emulator runs on the Windows host; adb from WSL reaches it through the Windows adb
server):

```
export ADB_SERVER_SOCKET=tcp:127.0.0.1:5037
adb devices            # emulator-5554, API 36, x86_64 with arm64 NDK translation
```

The owner's phone is reachable over wireless adb (its address is local configuration and is not
committed); more detail in `planning/ENVIRONMENT.md` and `/mnt/c/dev/experiments/phone/`.

## Hard rules

- `:core` is `kotlin("jvm")`. **Never** import `android.*` or any AndroidX artifact there. `:core` is
  where all logic lives, and it must stay testable with `./gradlew :core:test` and nothing else.
- Interfaces under `core/src/main/kotlin/ph/**` are frozen (`SPEC.md` §2). Do not change a signature
  without the orchestrator; raise it instead.
- **No literal tunables.** Timeouts, budgets, caps, retry counts, thresholds come from `Preset`,
  `Budgets`, or `LoopConfig`. A bare number in an implementation file is a defect.
- Tool schemas are byte-stable: fixed field order, serialised once, identical in both modes.
- Model-visible ⟺ logged: anything that reaches a request is reconstructable from the session log.
- Errors are values, never exceptions: `ToolOutcome.Err`, `ModelOutcome.Failure` reach the model as
  text, and never a stack trace.
- Edit files with targeted patches; do not rewrite files you do not own. File ownership is by path
  prefix and is assigned per workstream in `planning/PLAN.md`.
- One test file per source file, alongside it in `core/src/test/kotlin/`. Line coverage on `:core` must
  be **at least 95%** (`./gradlew :core:coverageGate` prints the number) — a workstream is not done
  because it compiles.

## What v1 deliberately does not have

Multi-agent, subagents, MCP, skills, hooks, plan mode, todo tools, code RAG/embeddings, cost meter,
token streaming, dark mode, i18n, GitHub export, session forking, OS sandboxing, Play distribution.
Absence here is a decision, not an omission — `planning/SPEC.md` §5.
