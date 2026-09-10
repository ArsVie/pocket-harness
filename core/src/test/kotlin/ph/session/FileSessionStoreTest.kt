package ph.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import ph.testing.FakeClock
import java.io.File

/** SPEC §2.3 — the file-backed store, the `index.json` cache, and delete semantics. */
class FileSessionStoreTest {

    @TempDir
    lateinit var root: File

    private val clock = FakeClock(1_000)

    @Test
    fun `list returns newest first`() {
        val store = FileSessionStore(root, clock)
        val older = store.create("/ws/older", "minimal")
        older.append(SessionEvent.UserMessage(0, 0, "first"))
        clock.advance(50)
        val newer = store.create("/ws/newer", "minimal")
        newer.append(SessionEvent.UserMessage(0, 0, "second"))

        val rows = store.list()
        assertEquals(listOf(newer.header.id, older.header.id), rows.map { it.id })
        assertEquals(listOf("second", "first"), rows.map { it.title })
        assertEquals(listOf("/ws/newer", "/ws/older"), rows.map { it.cwd })
    }

    @Test
    fun `deleting index json loses nothing`() {
        val store = FileSessionStore(root, clock)
        val a = store.create("/ws/a", "minimal")
        a.append(SessionEvent.UserMessage(0, 0, "alpha"))
        clock.advance(10)
        val b = store.create("/ws/b", "minimal")
        b.append(SessionEvent.UserMessage(0, 0, "beta"))

        val before = store.list()
        val index = File(root, "index.json")
        assertTrue(index.isFile, "list() writes the cache")
        assertTrue(index.delete())

        assertEquals(before, store.list())
    }

    @Test
    fun `a corrupt index json is rebuilt`() {
        val store = FileSessionStore(root, clock)
        store.create("/ws", "minimal").append(SessionEvent.UserMessage(0, 0, "hi"))
        File(root, "index.json").writeText("{ this is not json")

        val rows = store.list()
        assertEquals(1, rows.size)
        assertTrue(File(root, "index.json").readText().startsWith("["), "cache rewritten")
    }

    @Test
    fun `the index row matches a full scan`() {
        val store = FileSessionStore(root, clock)
        val session = store.create("/ws", "minimal")
        session.append(SessionEvent.UserMessage(0, 0, "hello there"))
        session.append(SessionEvent.AssistantMessage(0, 0, 1, "hi"))

        val row = store.list().single()
        assertEquals(session.header.id, row.id)
        assertEquals("/ws", row.cwd)
        assertEquals("hello there", row.title)
        assertEquals(1, row.lastSeq)
        assertEquals((session.events.last()).time, row.updatedAt)
    }

    @Test
    fun `title is truncated at exactly sixty characters`() {
        val store = FileSessionStore(root, clock)
        store.create("/ws", "minimal").append(SessionEvent.UserMessage(0, 0, "x".repeat(80)))
        assertEquals("x".repeat(60), store.list().single().title)
    }

    @Test
    fun `an empty session uses the header createdAt, its id as title, and lastSeq minus one`() {
        val store = FileSessionStore(root, clock)
        val session = store.create("/ws", "minimal")

        val row = store.list().single()
        assertEquals(session.header.id, row.id)
        assertEquals(session.header.id, row.title)
        assertEquals(session.header.createdAt, row.updatedAt)
        assertEquals(-1, row.lastSeq)
    }

    @Test
    fun `an empty root lists nothing`() {
        assertEquals(0, FileSessionStore(File(root, "missing"), clock).list().size)
    }

    @Test
    fun `non-session entries in the root are ignored`() {
        val store = FileSessionStore(root, clock)
        store.create("/ws", "minimal")
        File(root, "stray.txt").writeText("hi")
        File(root, "empty-dir").mkdirs()
        assertEquals(1, store.list().size)
    }

    @Test
    fun `delete removes the directory and a missing id is not an error`() {
        val store = FileSessionStore(root, clock)
        val session = store.create("/ws", "minimal")
        assertTrue(File(root, session.header.id).isDirectory)

        store.delete(session.header.id)
        assertFalse(File(root, session.header.id).exists())
        assertTrue(store.list().isEmpty())

        store.delete("never-existed")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `open replays a created session`() {
        val store = FileSessionStore(root, clock)
        val session = store.create("/ws", "minimal")
        session.append(SessionEvent.UserMessage(0, 0, "keep me"))
        session.close()

        val reopened = store.open(session.header.id)
        assertEquals(session.header, reopened.header)
        assertEquals(1, reopened.events.size)
        reopened.close()
    }

    @Test
    fun `open on a missing session fails`() {
        assertFailsWith<IllegalArgumentException> {
            FileSessionStore(root, clock).open("absent")
        }
    }
}
