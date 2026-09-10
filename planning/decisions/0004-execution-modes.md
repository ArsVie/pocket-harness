# ADR-PH-004 — Execution modes: a DEFAULT/YOLO switch over a frozen floor

| Status | Accepted |
|---|---|
| Date | September 2026 |
| Author | Ars (decision), Hermes (record) |
| Supersedes | amends `0002-safety-floor.md` (its "three-mode approval" deferral) |

## Context

ADR-002 deferred three-mode approval (PLAN / DEFAULT / YOLO) on the grounds that one floor was enough
for a single trusted user. Part 2 revises that: a two-mode switch is a product requirement, because
the same harness is used for supervised work and for letting the agent run unattended.

Decision recorded verbatim, as given:

> Almost, a default/yolo mode as a slider or switch needed.

## Decision

1. **Two execution modes**, presented as a single switch (Material switch in Settings; the 2010
   chrome renders it as a checkbox row): **DEFAULT** and **YOLO**. PLAN mode stays deferred — there
   is no read-only exploration mode and no plan artifact.
2. **DEFAULT**: commands do not run until the folder is trusted. First execution attempt in a project
   folder raises one prompt — *"Allow command execution in \<path\>?"* — and the answer is persisted
   per folder (project settings row), never per command. After trust, behaviour is identical to YOLO.
3. **YOLO**: no trust gate; every command runs.
4. **The floor is above the mode switch.** A small never-allowed set is frozen at app start and
   applies in both modes; no model output, fetched page, or settings edit can relax it at runtime.
   There is deliberately **no bypass flag to freeze** (ADR-002's named deferral), because the switch
   is not a bypass — it only skips the trust gate.
5. **`rm` maps to trash in both modes**, via a PATH shim shipped with the userland (a `rm` wrapper
   ahead of busybox's applet on `PATH`), moving targets under the workspace `.trash/`. No restore UI.
   Direct `unlink` by a non-intercepted binary remains uncovered (ADR-002's known limitation stands).
6. **Per-command budget: 300 s** wall clock, matching the reference preset's `timeoutMs: 300000`.
   Output clipping stays as planned (2000 lines / 32 KB head+tail, full text spilled to a file).

## Consequences

**Upside.** One boolean the user can reason about, no mode matrix in the loop, and the trust prompt
happens once per folder instead of once per command. The floor remains independently auditable.

**Downside.** YOLO on a phone with a real shell is a real blast radius; the mitigation is the floor
plus trash, not isolation (ADR-002 accepted this). A trusted folder is trusted forever until revoked
from project settings — revocation must exist in Settings, not only at first run.

## Implementation gaps (open)

- Where the trust row lives: project settings file vs. the session index.
- Whether the mode is per-project or per-thread. Working assumption: per-project, switchable at any
  time; a running turn uses the mode captured when the turn started.
- Trash retention: unbounded vs. cap (working assumption: unbounded for the PoC, listed in Settings).

## References

- `0002-safety-floor.md` (floor shape, deferrals, `rm` → trash as ours).
- `../research/02-deepseek-harness-minimal.md` §3.3 (`timeoutMs: 300000`), §3.5.
