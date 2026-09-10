# ADR-PH-003 — Model wire: OpenAI chat-completions, non-streaming, `reasoning_effort` as the lever

| Status | Accepted |
|---|---|
| Date | September 2026 |
| Author | Ars (decision), Hermes (record) |
| Supersedes | — (refines the DeepSeek wire notes in `../research/02`) |

## Context

`../research/02-deepseek-harness-minimal.md` §5 documents the DeepSeek adapter's live wire contract:
always `stream: true` with `stream_options: {include_usage: true}`, and two top-level body fields,
`thinking: {type: 'enabled'|'disabled'}` and `reasoning_effort: 'high'|'max'`.

Two of those are DeepSeek-adapter implementation details, not properties of our client. We control a
single generic OpenAI-compatible endpoint, and the interface decision is that the reply arrives whole.

Decision recorded verbatim, as given:

> Correct, we will use openAi reasoning_effort lever.
> No token streaming

## Decision

1. **Transport:** one `POST {baseUrl}/chat/completions`, non-streaming. `stream` is not sent (never
   `true`). The response is read as a single JSON body; `choices[0].message` is the turn outcome.
2. **Reasoning lever:** the standard OpenAI `reasoning_effort` field, top-level in the request body.
   The DeepSeek-specific `thinking: {type}` object is **not** sent — it is one vendor's spelling and
   we are not that vendor's client.
3. **Levels are data.** The `reasoning_effort` value set and the default come from the model entry in
   the settings file (a `reasoning_efforts[]` list plus a `default`), not from Kotlin constants. An
   unknown/unsupported level fails locally as `UNSUPPORTED_REASONING_EFFORT` before any I/O, matching
   the reference's validate-before-send behaviour.
4. **Reasoning text is persisted, collapsed in the UI, and replayed only on tool-call turns**
   (reference `serialize.ts:96-99`): a prior assistant message carries `reasoning_content` back into
   the request only when that message also carried tool calls. Purely conversational reasoning is not
   replayed — it stays in the transcript for the user.
   Inbound field name is accepted as `reasoning_content` or `reasoning` (one accessor, first non-null).
5. **Errors are model-visible, never exceptions.** Transport/HTTP failures produce the same sealed
   outcome type as tool failures and render in-thread. Auto-retry: at most 2 retries on 429 and 5xx
   with exponential backoff; no silent model failover, no provider fan-out. `401/403` →
   `MISSING_CREDENTIAL`/`AUTH_REJECTED` and no retry.
6. **Endpoint config is a route row:** `{ baseUrl, model, apiKeyRef, reasoningEfforts[], default }`,
   user-editable in Settings. The key itself lives in `EncryptedSharedPreferences`, referenced by
   name, never inlined in a config file or committed.

## Consequences

**Upside.** No SSE parser, no incremental assembler, no partial-message persistence — the whole
`StreamChunk`/BlockAssembler layer of the reference (`agent.ts:349-390`) is deleted before it is
written. One request, one response, one transcript entry. Cache hygiene still applies and is now
easier to guarantee: the request body is built once per step from a byte-stable prefix.

**Downside.** No first-token feedback: a turn is opaque until it completes. Mitigated in the UI by a
progress state that reports *what* is happening (request sent, waiting, tool running) rather than
tokens. Long turns feel slower than they are. Accepted.

**Revisit trigger.** Any move to a hosted UI where perceived latency matters more than simplicity.

## References

- `../research/02-deepseek-harness-minimal.md` §3.4, §5 (wire contract, reasoning replay rule).
- `../research/03-arxiv-harness-engineering.md` §10 (Pattern 11 stable prefix; Pattern 24 client-side
  transport failure modes).
