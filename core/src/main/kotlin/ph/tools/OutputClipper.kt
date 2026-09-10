package ph.tools

import ph.prompt.Budgets
import java.io.File

/**
 * Head+tail output clipping (SPEC §2.4). Keeps at most [Budgets.maxOutputLines] lines **and**
 * [Budgets.maxOutputChars] characters, whichever bites first:
 *
 * - characters: when the text is longer than `maxOutputChars`, the head keeps `spillHeadChars` and
 *   the tail keeps `spillTailChars`;
 * - lines: when the text has more than `maxOutputLines` lines, the head keeps half the line budget
 *   and the tail the other half.
 *
 * Each side takes the *tighter* of the two cuts, so a text can be bounded on both axes at once. The
 * full, un-clipped text is written to `<spillDir>/<callId>.txt` and the clipped text ends with
 * `[truncated: full output at <path>]`. Under the limits the text is returned unchanged and the
 * spill path is `null` — exactly-at-limit is not truncated, one over is.
 *
 * The caps are the caller's [Budgets]; this class holds no tunable of its own.
 */
class OutputClipper(private val budgets: Budgets) {

    /** The model-visible text and, when anything was dropped, where the full text lives. */
    data class Clipped(val text: String, val spillPath: String?)

    /**
     * Clips [text], spilling the whole of it under [spillDir] named `<callId>.txt` when a cap
     * actually bites. [callId] makes the spill file addressable from the transcript.
     */
    fun clip(text: String, spillDir: String, callId: String): Clipped {
        val lineActive = lineCount(text) > budgets.maxOutputLines
        val charActive = text.length > budgets.maxOutputChars

        val charHeadEnd = if (charActive) minOf(budgets.spillHeadChars, text.length) else text.length
        val charTailStart = if (charActive) maxOf(text.length - budgets.spillTailChars, 0) else 0

        val starts = lineStarts(text)
        val headLines = budgets.maxOutputLines / LINE_SPLIT_DIVISOR
        val tailLines = budgets.maxOutputLines - headLines
        val lineHeadEnd =
            if (lineActive && headLines < starts.size) starts[headLines] else text.length
        val lineTailStart =
            if (lineActive && tailLines in 1 until starts.size) starts[starts.size - tailLines] else 0

        val headEnd = minOf(charHeadEnd, lineHeadEnd)
        val tailStart = maxOf(charTailStart, lineTailStart)
        if (headEnd >= tailStart) return Clipped(text, null)

        val file = File(spillDir, "$callId.txt")
        file.parentFile?.mkdirs()
        file.writeText(text)

        val path = file.absolutePath
        return Clipped(
            text = text.substring(0, headEnd) + text.substring(tailStart) + "\n" + marker(path),
            spillPath = path,
        )
    }

    private fun marker(path: String): String = "$TRUNCATION_MARKER$path]"

    /**
     * Offsets at which each non-phantom line starts. A trailing newline does not begin a new line,
     * so `"a\nb\n"` has two lines and an empty string has none.
     */
    private fun lineStarts(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val starts = ArrayList<Int>()
        starts.add(0)
        for (i in text.indices) {
            if (text[i] == '\n' && i + 1 < text.length) starts.add(i + 1)
        }
        return starts
    }

    private fun lineCount(text: String): Int = lineStarts(text).size

    private companion object {
        /** Head/tail share of the line budget; the character split is budgeted explicitly. */
        const val LINE_SPLIT_DIVISOR = 2
        const val TRUNCATION_MARKER = "[truncated: full output at "
    }
}
