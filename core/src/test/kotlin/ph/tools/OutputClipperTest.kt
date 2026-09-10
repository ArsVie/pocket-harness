package ph.tools

import org.junit.jupiter.api.io.TempDir
import ph.prompt.Budgets
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Boundary arithmetic for [OutputClipper]: exactly-at-limit is not truncated, one over is. */
class OutputClipperTest {

    @TempDir
    lateinit var dir: File

    /** Characters never bite on their own here; the line cap is what is under test. */
    private val lineBudgets = Budgets(maxOutputLines = 4, maxOutputChars = 1_000)
    private val charBudgets =
        Budgets(maxOutputLines = 1_000, maxOutputChars = 10, spillHeadChars = 4, spillTailChars = 2)
    private val bothBudgets =
        Budgets(maxOutputLines = 2, maxOutputChars = 6, spillHeadChars = 2, spillTailChars = 2)

    private fun clip(text: String, budgets: Budgets, spillDir: String = dir.path) =
        OutputClipper(budgets).clip(text, spillDir, "call-1")

    @Test
    fun `text under both limits comes back unchanged with no spill`() {
        val result = clip("a\nb\n", lineBudgets)
        assertEquals("a\nb\n", result.text)
        assertNull(result.spillPath)
    }

    @Test
    fun `empty text is not truncated`() {
        val result = clip("", lineBudgets)
        assertEquals("", result.text)
        assertNull(result.spillPath)
    }

    @Test
    fun `exactly at the character limit is not truncated`() {
        val result = clip("0123456789", charBudgets)
        assertEquals("0123456789", result.text)
        assertNull(result.spillPath)
    }

    @Test
    fun `one character over the character limit is truncated and spilled`() {
        val result = clip("0123456789A", charBudgets)
        val path = File(dir, "call-1.txt").absolutePath
        assertEquals("01239A\n[truncated: full output at $path]", result.text)
        assertEquals(path, result.spillPath)
        assertEquals("0123456789A", File(path).readText())
    }

    @Test
    fun `exactly at the line limit is not truncated`() {
        val result = clip("l1\nl2\nl3\nl4\n", lineBudgets)
        assertEquals("l1\nl2\nl3\nl4\n", result.text)
        assertNull(result.spillPath)
    }

    @Test
    fun `one line over the line limit keeps head and tail lines`() {
        val text = "l1\nl2\nl3\nl4\nl5\n"
        val result = clip(text, lineBudgets)
        val path = File(dir, "call-1.txt").absolutePath
        assertEquals("l1\nl2\nl4\nl5\n\n[truncated: full output at $path]", result.text)
        assertEquals(path, result.spillPath)
        assertEquals(text, File(path).readText())
    }

    @Test
    fun `whichever cap bites first wins on each side`() {
        val text = "aaaa\nbbbb\ncccc\ndddd\n"
        val result = clip(text, bothBudgets)
        val path = File(dir, "call-1.txt").absolutePath
        // the character cap takes 2 head chars (tighter than a whole line) and 2 tail chars,
        // while the line cap would have kept a line at each end.
        assertEquals("aad\n\n[truncated: full output at $path]", result.text)
        assertEquals(text, File(path).readText())
    }

    @Test
    fun `the spill directory is created when it does not exist`() {
        val nested = File(dir, "spill/nested")
        val result = clip("0123456789A", charBudgets, nested.path)
        assertEquals(File(nested, "call-1.txt").absolutePath, result.spillPath)
        assertEquals("0123456789A", File(result.spillPath!!).readText())
    }
}
