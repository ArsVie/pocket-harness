package ph.session

import ph.policy.ExecutionMode

/**
 * Re-derives the execution mode a session is running under — SPEC §2.3, mirroring the reference's
 * `resolveSessionPreset` (research `02-deepseek-harness-minimal.md` §2).
 *
 * Mode is never read from the header: the header records only the preset id, and a mode can change
 * mid-session. The last `ModeSelected` wins, found by scanning events **backwards**. When no event
 * ever carried a mode, the preset's default applies.
 */
fun resolveSessionMode(events: List<SessionEvent>, presetDefault: ExecutionMode): ExecutionMode {
    for (index in events.indices.reversed()) {
        val event = events[index]
        if (event is SessionEvent.ModeSelected) return event.mode
    }
    return presetDefault
}

/** [resolveSessionMode] over a whole session, using the header's preset to supply the default. */
fun resolveSessionMode(
    header: SessionHeader,
    events: List<SessionEvent>,
    presetDefault: ExecutionMode,
): ExecutionMode = resolveSessionMode(events, presetDefault)
