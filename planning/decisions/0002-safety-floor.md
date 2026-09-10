# ADR-PH-002 — Safety: policy floor only, with `rm` mapped to trash

| Status | Accepted |
|---|---|
| Date | September 2026 |
| Author | Ars (decision), Hermes (record) |
| Supersedes | — |
| Superseded by | — |

## Context

The research corpus is unusually clear on this point, and the decision follows it directly.

Observation 6 (paper, §13): *"OS-level sandboxing is one of the most code-expensive capabilities in the corpus, and it
is a choice rather than a consequence of scale."* The April edition's correlation — that the largest systems are the
sandboxers — was falsified by the expanded corpus: Hermes ships *"zero OS-level isolation primitives"* and OpenCode is
policy-only, both at the top of the size range. Where a native sandbox *is* built, it is *"a substantial
multi-thousand-line investment"*; Codex vendors bubblewrap into its tree, and the meta-harness pays the same bill again
one layer up. Pi refuses outright, calling a partial in-process sandbox *"easy to misunderstand as a security boundary."*

The paper's own scaffold omits sandboxing for this reason, stating that it *"deliberately omits features for which our
corpus shows divergence: sandbox (Recommendations 9–10 are deployment-specific)."*

Section 15.3 names exactly our situation: *"The minimal safety surface in Aider and Mini-SWE-Agent is defensible for
their target use case (developer tools with trusted users)."* This is a single-user PoC on the owner's own device.

Decision recorded verbatim, as given:

> only having a floor level security and mapping rm to trash is enough.

## Decision

Floor-level security. Two mechanisms, nothing more:

1. A small **never-allowed floor**, frozen at app start, that model output and fetched content cannot relax.
2. **`rm` maps to trash** rather than unlink, so destructive filesystem operations are recoverable.

This is a deliberate scope reduction for a PoC, not a claim that the full stack is unnecessary. The floor's *shape* is
what is being adopted; its contents are ours.

## Grounding, and what is ours

Per "integrate, don't fabricate" — the distinction matters:

- **From the paper (Recommendation 11, verbatim):** *"Regardless of tier, codify safety rules as data or dedicated
  policy files rather than as imperative code—and if you support a YOLO mode, keep a floor beneath it."* The cited
  lesson is Hermes's: *"its twelve hardline patterns survive --yolo, with the bypass flag frozen at module import so
  injected content cannot flip it at runtime."*
- **From the paper (§10.7, verbatim):** Hermes's floor patterns are *"rm -rf /, mkfs, dd to block devices, fork bombs,
  shutdown"*.
- **Ours, with no source claiming it:** the `rm` → trash mapping. No reference in the corpus does this, and the word
  "trash" does not appear anywhere in the paper. It is a decision, not a finding, and the dossier should not blur the two.

## Consequences

**Upside.** Safety costs a policy file and a shim, not a subsystem. The floor is auditable independently of the loop
(Rec 11's stated reason for policy-as-data) and survives a refactor of the tool layer.

**Downside.** No OS-level isolation: a sufficiently creative command can still damage the userland. Accepted for a
single-user PoC on the owner's device. The `rm` → trash mapping only covers code paths that go through it; a binary
invoked by the shell that unlinks directly is not intercepted. This is a known limitation, not a guarantee.

**Revisit triggers.** Multi-user or automated deployment; any promptware ingestion path; the moment the harness runs
commands the user did not originate.

## Explicitly deferred (named, so absence is a choice and not an oversight)

| Deferred | Source | Why deferred |
|---|---|---|
| OS-level sandboxing | Rec 10 | Deployment-specific; thousands of lines; not our tier |
| Three-mode approval (PLAN / DEFAULT / YOLO) | Rec 9 | One floor is enough for a single trusted user; no mode switcher |
| Syntax-aware command permissioning | Pattern 27 | Requires tree-sitter parsing; OpenCode/Hermes-tier investment |
| Auxiliary-LLM approval gate | Hermes (`_smart_approve`) | Adds a model call per flagged command |
| Promptware / injection scanning | Hermes (Tirith) | No external content ingestion in scope yet |
| Frozen bypass flag | Rec 11 | There is no bypass mode to freeze a flag against |

## Implementation gaps (open)

- Whether the floor is data (a policy file parsed at startup) or a frozen Kotlin object; Rec 11 prefers data.
- What the floor's contents are for a Termux userland, where `rm -rf /` is not the only destructive shape.
- Whether `rm` → trash is a PATH shim, a shell function, or an exec-layer intercept.

## References

- arXiv:2609.00006v1 §10.7 (Hermes policy floor), §13 Obs 6, §15.3, §16.6 Rec 9–11, §16.10 Listing 3.
- `planning/research/03-arxiv-harness-engineering.md` §3 (Obs 6), §5, §8 (Rec 9–11), §9 (scaffold).
