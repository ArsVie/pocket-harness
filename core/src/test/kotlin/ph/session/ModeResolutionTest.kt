package ph.session

import kotlin.test.Test
import kotlin.test.assertEquals
import ph.policy.ExecutionMode

/** SPEC §2.3 — mode is re-derived from events, never read from the header. */
class ModeResolutionTest {

    @Test
    fun `the last mode selected wins over an earlier one`() {
        val events = listOf(
            SessionEvent.ModeSelected(0, 0, ExecutionMode.YOLO),
            SessionEvent.UserMessage(1, 0, "hi"),
            SessionEvent.ModeSelected(2, 0, ExecutionMode.DEFAULT),
        )
        assertEquals(ExecutionMode.DEFAULT, resolveSessionMode(events, ExecutionMode.YOLO))
    }

    @Test
    fun `the fallback applies when no mode was ever selected`() {
        val events = listOf(
            SessionEvent.TurnStart(0, 0, 1),
            SessionEvent.UserMessage(1, 0, "hi"),
        )
        assertEquals(ExecutionMode.YOLO, resolveSessionMode(events, ExecutionMode.YOLO))
        assertEquals(ExecutionMode.DEFAULT, resolveSessionMode(emptyList(), ExecutionMode.DEFAULT))
    }

    @Test
    fun `the header overload prefers the last selection`() {
        val header = SessionHeader("s", 0, "/ws", "minimal")
        assertEquals(
            ExecutionMode.DEFAULT,
            resolveSessionMode(header, listOf(SessionEvent.ModeSelected(0, 0, ExecutionMode.DEFAULT)), ExecutionMode.YOLO),
        )
    }

    @Test
    fun `the header overload falls back to the preset default`() {
        val header = SessionHeader("s", 0, "/ws", "minimal")
        assertEquals(ExecutionMode.YOLO, resolveSessionMode(header, emptyList(), ExecutionMode.YOLO))
    }
}
