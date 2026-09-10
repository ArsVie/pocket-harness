# ADR-PH-001 — Execution surface: on-device, Termux-style

| Status | Accepted |
|---|---|
| Date | September 2026 |
| Author | Ars (decision), Hermes (record) |
| Supersedes | — |
| Superseded by | — |

## Context

All three research references assume the harness has a real shell on a real filesystem:

- Pi ships `bash` as a subprocess tool (`packages/coding-agent/src/core/tools/bash.ts`).
- The DeepSeek `minimal` preset's entire capability is a persistent PTY `bash` with `timeoutMs: 300000` plus a
  `str_replace_editor`.
- The paper's minimum-viable-harness scaffold (§16.10, Listing 3) is `bash` + three file tools.

An Android app running in the normal application sandbox has no such shell. That gap — not the loop, prompt, or tool
schemas — is the largest single delta between the references and this project, and it had to be decided before any
planning could be honest about scope.

Decision recorded verbatim, as given:

> Everything will run on the phone termux style, we will use the tips for minimal security in this PoC from the
> research paper. as it mentions it being the most complexity for less benefit out of every component, only having a
> floor level security and mapping rm to trash is enough. Stop worrying about time or feasibility, those are things
> that I'll manage. The dossier should be as clean as possible from reading between the lines of the docs, integrate
> don't fabricate.

## Options considered

| Option | Mechanism | Verdict |
|---|---|---|
| On-device userland (Termux-style) | Ship a shell + userland into the app; tools are real `bash` invocations against a real filesystem | **Chosen** |
| Native file tools only | Pure Kotlin read/write/replace/search/list over an app-private workspace; no shell | Rejected — loses the reference `bash` primitive entirely |
| Sandboxed exec, fixed command vocabulary | An exec tool accepting only a known set of commands; no arbitrary shell | Rejected — keeps the schema but not the capability |
| Remote workspace | Phone is a client of a dev box over HTTP/SSH; execution happens off-device | Rejected — contradicts "everything runs on the phone" |

The chosen option is the only one that preserves structural fidelity to all three references: the tool surface stays
`bash` + a string-replace editor with no translation layer, because the runtime they were written against is the
runtime we are actually providing.

## Decision

Execution happens on the device, in a Termux-style userland. The harness is a real shell over a real filesystem, so the
reference tool surface transfers without reinterpretation.

## Consequences

**Upside.** The `bash` tool, the two-tool minimal preset, and the scaffold's tool set all transfer verbatim. Tool
descriptions, truncation budgets (2000 lines / 50 KB), and exit-code conventions can be ported from the references
rather than invented.

**Downside — a hard platform constraint, recorded here so it is not rediscovered later.** Android 10 (API 29) removed
execute permission for the app home directory. The official behaviour-change note: *"Removed execute permission for app
home directory [W^X violation]. Apps should load only the binary code that's embedded within an app's APK file.
Untrusted apps that target Android 10 cannot invoke `execve()`."* Two mechanisms are known to work:

1. Pin `targetSdk` ≤ 28. This is how Termux itself still runs; it is why Termux cannot publish new versions to Play.
2. Ship the executables inside the APK's native library directory (`jniLibs`) with `android:extractNativeLibs="true"`.
   Exec from the read-only `/data/app/.../lib/` path remains permitted. The Termux maintainer's own position in the
   tracking issue is that in-APK packaging is "the most correct solution"; interpreted executables (e.g. through a
   machine-code interpreter) also continue to work from app data.

For a local PoC, either path is acceptable and neither is a blocker. Play distribution is out of scope; do not treat
Play policy as a design constraint on this build.

**Migration path.** Not applicable — greenfield.

## Implementation gaps (open, not resolved by this ADR)

- Which userland ships: busybox only, or a full Termux bootstrap.
- Package installation: none, or a curated set pinned at build time.
- Whether tools shell out to a bundled binary, or are implemented natively over JNI and merely *present* as `bash`.

## References

- Android Developers. *Behavior changes: apps targeting API 29+* — "Removed execute permission for app home directory".
  https://developer.android.com/about/versions/10/behavior-changes-10
- termux/termux-app issue #1072, "No more exec from data folder on targetAPI >= Android Q".
  https://github.com/termux/termux-app/issues/1072
- `planning/research/01-pi-harness.md` §4; `planning/research/02-deepseek-harness-minimal.md` §3.3, §10;
  `planning/research/03-arxiv-harness-engineering.md` §9.
