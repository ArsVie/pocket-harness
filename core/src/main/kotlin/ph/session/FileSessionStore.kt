package ph.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ph.ports.Clock
import java.io.File
import java.util.UUID

/**
 * File-backed [SessionStore] — SPEC §2.3. One directory per session under [rootDir], each holding a
 * `session.jsonl`; [list] is derived by replaying those logs.
 *
 * `index.json` is a **cache only**. It is written by every [create]/[list]/[delete] so other readers
 * can show an inbox without parsing every log, but [list] never trusts it: appends happen through
 * [Session] (not through the store), so a cached row can go stale the moment an event lands. Scanning
 * is authoritative, which is also why deleting `index.json` loses nothing.
 */
class FileSessionStore(
    private val rootDir: File,
    private val clock: Clock,
    /** Where [delete] moves session directories (B-15) — a sibling of [rootDir] by default. */
    private val trashDir: File = File(rootDir.parentFile, TRASH_SUBDIR),
) : SessionStore {

    override fun create(cwd: String, presetId: String): Session {
        rootDir.mkdirs()
        val header = SessionHeader(
            id = newSessionId(),
            createdAt = clock.nowMillis(),
            cwd = cwd,
            presetId = presetId,
        )
        val session = JsonlSessionLog(rootDir, header, clock)
        writeIndex(scan())
        return session
    }

    override fun open(id: String): Session = JsonlSessionLog.open(rootDir, id, clock)

    override fun list(): List<SessionSummary> {
        val rows = scan().sortedWith(NEWEST_FIRST)
        writeIndex(rows)
        return rows
    }

    override fun delete(id: String) {
        val dir = File(rootDir, id)
        if (!dir.isDirectory) return
        trashDir.mkdirs()
        val target = File(trashDir, clock.nowMillis().toString() + "-" + id)
        if (!dir.renameTo(target)) {
            // Cross-device or racing rename: copy then drop, so a delete never loses data silently.
            dir.copyRecursively(target, overwrite = true)
            dir.deleteRecursively()
        }
        writeIndex(scan())
    }

    private fun scan(): List<SessionSummary> {
        if (!rootDir.isDirectory) return emptyList()
        val rows = mutableListOf<SessionSummary>()
        for (child in rootDir.listFiles().orEmpty()) {
            val log = File(child, JsonlSessionLog.SESSION_FILE_NAME)
            if (!child.isDirectory || !log.isFile) continue
            val replay = replaySessionFile(log)
            val firstUser = replay.events.filterIsInstance<SessionEvent.UserMessage>().firstOrNull()
            val manual = replay.events.filterIsInstance<SessionEvent.SessionTitle>()
                .map { it.title.trim() }
                .lastOrNull { it.isNotEmpty() }
            val last = replay.events.lastOrNull()
            rows += SessionSummary(
                id = replay.header.id,
                cwd = replay.header.cwd,
                title = manual?.take(TITLE_MAX_CHARS)
                    ?: firstUser?.text?.take(TITLE_MAX_CHARS)
                    ?: replay.header.id,
                updatedAt = last?.time ?: replay.header.createdAt,
                lastSeq = last?.seq ?: LAST_SEQ_EMPTY,
            )
        }
        return rows
    }

    private fun writeIndex(rows: List<SessionSummary>) {
        rootDir.mkdirs()
        val array = JsonArray(
            rows.map { row ->
                buildJsonObject {
                    put("id", row.id)
                    put("cwd", row.cwd)
                    put("title", row.title)
                    put("updatedAt", row.updatedAt)
                    put("lastSeq", row.lastSeq)
                }
            },
        )
        File(rootDir, INDEX_FILE_NAME).writeText(array.toString())
    }

    private fun newSessionId(): String = SESSION_ID_PREFIX + UUID.randomUUID()

    private companion object {
        const val INDEX_FILE_NAME = "index.json"
        const val SESSION_ID_PREFIX = "session-"

        /** SPEC §2.3: the first `UserMessage`'s text is truncated to 60 chars for the inbox title. */
        const val TITLE_MAX_CHARS = 60

        /** B-15 delete: a deleted session's directory moves under `<parent>/trash/sessions/`. */
        const val TRASH_SUBDIR = "trash/sessions"

        /** No events ⇒ the session's last seq is -1 (SPEC §2.3). */
        const val LAST_SEQ_EMPTY = -1

        /** Newest first; the id breaks ties so two sessions made in one millisecond stay stable. */
        val NEWEST_FIRST: Comparator<SessionSummary> =
            compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.id }
    }
}
