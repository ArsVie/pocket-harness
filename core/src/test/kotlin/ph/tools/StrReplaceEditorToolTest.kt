package ph.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import ph.model.ToolCallRequest
import ph.policy.ExecutionMode
import ph.prompt.Budgets
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class StrReplaceEditorToolTest {

    @TempDir
    lateinit var tmp: File

    private val tool = StrReplaceEditorTool(Budgets())

    private fun invoke(json: String): ToolOutcome = runBlocking {
        tool.run(
            ToolCallRequest(id = "call-1", name = ToolSchemas.EDITOR_NAME, argumentsJson = json),
            ToolContext(cwd = tmp.absolutePath, mode = ExecutionMode.DEFAULT, callId = "call-1"),
        )
    }

    private fun ok(json: String): ToolOutcome.Ok = assertIs<ToolOutcome.Ok>(invoke(json))

    private fun err(json: String): ToolOutcome.Err = assertIs<ToolOutcome.Err>(invoke(json))

    private fun write(name: String, bytes: ByteArray): File {
        val file = File(tmp, name)
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    private fun write(name: String, text: String): File = write(name, text.toByteArray(Charsets.UTF_8))

    private fun view(path: String, range: String? = null): String {
        val rangeArg = if (range == null) "" else ""","view_range":$range"""
        return ok("""{"command":"view","path":"$path"$rangeArg}""").text
    }

    // ------------------------------------------------------------------ view (file)

    @Test
    fun `view renders a file with cat -n style six wide gutters`() {
        write("f.txt", "alpha\nbeta\ngamma\n")
        assertEquals(
            "     1\talpha\n     2\tbeta\n     3\tgamma",
            view(File(tmp, "f.txt").absolutePath),
        )
    }

    @Test
    fun `view honours view_range start to end with -1`() {
        write("f.txt", "one\ntwo\nthree\nfour\n")
        assertEquals(
            "     2\ttwo\n     3\tthree\n     4\tfour",
            view(File(tmp, "f.txt").absolutePath, "[2,-1]"),
        )
    }

    @Test
    fun `view honours an explicit two line range`() {
        write("f.txt", "one\ntwo\nthree\nfour\n")
        assertEquals("     2\ttwo\n     3\tthree", view(File(tmp, "f.txt").absolutePath, "[2,3]"))
    }

    @Test
    fun `view gutter is six wide right aligned at 1 9 10 100 and 1000`() {
        val content = (1..1000).joinToString("\n") { "line$it" } + "\n"
        val file = write("big.txt", content)
        val lines = view(file.absolutePath).split("\n")
        assertEquals("     1\tline1", lines[0])
        assertEquals("     9\tline9", lines[8])
        assertEquals("    10\tline10", lines[9])
        assertEquals("   100\tline100", lines[99])
        assertEquals("  1000\tline1000", lines[999])
    }

    @Test
    fun `view of an empty file is empty`() {
        val file = write("empty.txt", "")
        assertEquals("", view(file.absolutePath))
    }

    @Test
    fun `view of a missing file is FS_NOT_FOUND`() {
        val outcome = err("""{"command":"view","path":"${File(tmp, "nope.txt").absolutePath}"}""")
        assertEquals(ToolErrorCode.FS_NOT_FOUND, outcome.code)
    }

    // ------------------------------------------------------------------ view (range errors)

    @Test
    fun `view with a range of the wrong length is BAD_ARGUMENT`() {
        val file = write("f.txt", "a\nb\nc\n")
        val outcome = err("""{"command":"view","path":"${file.absolutePath}","view_range":[2]}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    @Test
    fun `view with a non integer range is BAD_ARGUMENT`() {
        val file = write("f.txt", "a\nb\nc\n")
        val outcome = err("""{"command":"view","path":"${file.absolutePath}","view_range":[1,"x"]}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    @Test
    fun `view with a start line out of range is BAD_ARGUMENT`() {
        val file = write("f.txt", "a\nb\nc\n")
        val outcome = err("""{"command":"view","path":"${file.absolutePath}","view_range":[9,10]}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    @Test
    fun `view with an end line out of range is BAD_ARGUMENT`() {
        val file = write("f.txt", "a\nb\nc\n")
        val outcome = err("""{"command":"view","path":"${file.absolutePath}","view_range":[2,9]}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    @Test
    fun `view with an inverted range is BAD_ARGUMENT`() {
        val file = write("f.txt", "a\nb\nc\n")
        val outcome = err("""{"command":"view","path":"${file.absolutePath}","view_range":[3,1]}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    // ------------------------------------------------------------------ view (directory)

    @Test
    fun `view of a directory lists two levels and excludes dotfiles and build caches`() {
        write("a.txt", "a")
        write("sub/inner.txt", "i")
        write("sub/deeper/x.txt", "x")
        write("sub/node_modules/lib.js", "l")
        write("node_modules/z.js", "z")
        write("__pycache__/p.pyc", "p")
        write(".hidden", "h")

        val text = view(tmp.absolutePath)

        assertTrue(text.startsWith("${tmp.absolutePath}:"), text)
        assertTrue(text.contains("a.txt"), text)
        assertTrue(text.contains("sub/"), text)
        assertTrue(text.contains("sub/inner.txt"), text)
        assertTrue(text.contains("sub/deeper/"), text)
        assertTrue(!text.contains("x.txt"), text)
        assertTrue(!text.contains(".hidden"), text)
        assertTrue(!text.contains("node_modules"), text)
        assertTrue(!text.contains("__pycache__"), text)
        assertTrue(!text.contains("z.js"), text)
        assertTrue(!text.contains("lib.js"), text)
    }

    @Test
    fun `view of a directory is capped by budgets maxOutputLines`() {
        for (i in 1..5) write("file$i.txt", "x")
        val capped = StrReplaceEditorTool(Budgets(maxOutputLines = 2))
        val outcome = runBlocking {
            capped.run(
                ToolCallRequest("c", ToolSchemas.EDITOR_NAME, """{"command":"view","path":"${tmp.absolutePath}"}"""),
                ToolContext(tmp.absolutePath, ExecutionMode.DEFAULT, "c"),
            )
        }
        val text = assertIs<ToolOutcome.Ok>(outcome).text
        assertEquals("${tmp.absolutePath}:\nfile1.txt\nfile2.txt\n", text)
    }

    // ------------------------------------------------------------------ create

    @Test
    fun `create writes the file and its exact bytes`() {
        val path = File(tmp, "new.txt").absolutePath
        ok("""{"command":"create","path":"$path","file_text":"hello\nworld"}""")
        assertEquals("hello\nworld", String(File(path).readBytes(), Charsets.UTF_8))
    }

    @Test
    fun `create refuses an existing file with FS_EXISTS`() {
        val file = write("exists.txt", "x")
        val outcome = err("""{"command":"create","path":"${file.absolutePath}","file_text":"y"}""")
        assertEquals(ToolErrorCode.FS_EXISTS, outcome.code)
    }

    @Test
    fun `create with a missing parent is FS_NOT_FOUND`() {
        val path = File(tmp, "missing/dir/new.txt").absolutePath
        val outcome = err("""{"command":"create","path":"$path","file_text":"y"}""")
        assertEquals(ToolErrorCode.FS_NOT_FOUND, outcome.code)
    }

    @Test
    fun `create without file_text is BAD_ARGUMENT`() {
        val path = File(tmp, "new.txt").absolutePath
        val outcome = err("""{"command":"create","path":"$path"}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    // ------------------------------------------------------------------ str_replace

    @Test
    fun `str_replace replaces exactly one occurrence`() {
        val file = write("f.txt", "the cat sat\non the mat\n")
        ok("""{"command":"str_replace","path":"${file.absolutePath}","old_str":"on the mat","new_str":"on the bed"}""")
        assertEquals("the cat sat\non the bed\n", String(file.readBytes(), Charsets.UTF_8))
    }

    @Test
    fun `str_replace with no new_str deletes the string`() {
        val file = write("f.txt", "keep me\nremove this line\n")
        ok("""{"command":"str_replace","path":"${file.absolutePath}","old_str":"remove this line\n"}""")
        assertEquals("keep me\n", String(file.readBytes(), Charsets.UTF_8))
    }

    @Test
    fun `str_replace preserves bytes outside the edit`() {
        val original = "a\r\nb\nc".toByteArray(Charsets.UTF_8)
        val file = write("f.txt", original)
        ok("""{"command":"str_replace","path":"${file.absolutePath}","old_str":"b","new_str":"B"}""")
        assertTrue(file.readBytes().contentEquals("a\r\nB\nc".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `str_replace of an absent string is FS_EDIT_NOT_FOUND`() {
        val file = write("f.txt", "abc\n")
        val outcome =
            err("""{"command":"str_replace","path":"${file.absolutePath}","old_str":"zzz","new_str":"q"}""")
        assertEquals(ToolErrorCode.FS_EDIT_NOT_FOUND, outcome.code)
    }

    @Test
    fun `str_replace of a non unique string is FS_AMBIGUOUS_EDIT naming the lines`() {
        val file = write("f.txt", "one\ntwo\none\nthree\none\n")
        val outcome =
            err("""{"command":"str_replace","path":"${file.absolutePath}","old_str":"one","new_str":"1"}""")
        assertEquals(ToolErrorCode.FS_AMBIGUOUS_EDIT, outcome.code)
        assertTrue(outcome.message.contains("1, 3, 5"), outcome.message)
    }

    @Test
    fun `str_replace on a missing file is FS_NOT_FOUND`() {
        val outcome = err(
            """{"command":"str_replace","path":"${File(tmp, "nope.txt").absolutePath}","old_str":"a","new_str":"b"}""",
        )
        assertEquals(ToolErrorCode.FS_NOT_FOUND, outcome.code)
    }

    @Test
    fun `str_replace without old_str is BAD_ARGUMENT`() {
        val file = write("f.txt", "abc\n")
        val outcome = err("""{"command":"str_replace","path":"${file.absolutePath}","new_str":"b"}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    @Test
    fun `str_replace with an empty old_str is BAD_ARGUMENT`() {
        val file = write("f.txt", "abc\n")
        val outcome = err("""{"command":"str_replace","path":"${file.absolutePath}","old_str":""}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    // ------------------------------------------------------------------ insert

    @Test
    fun `insert at line 0 prepends to the file`() {
        val file = write("f.txt", "a\nb\nc\n")
        ok("""{"command":"insert","path":"${file.absolutePath}","insert_line":0,"new_str":"X"}""")
        assertEquals("X\na\nb\nc\n", String(file.readBytes(), Charsets.UTF_8))
    }

    @Test
    fun `insert in the middle places the line after insert_line`() {
        val file = write("f.txt", "a\nb\nc\nd\n")
        ok("""{"command":"insert","path":"${file.absolutePath}","insert_line":2,"new_str":"X"}""")
        assertEquals("a\nb\nX\nc\nd\n", String(file.readBytes(), Charsets.UTF_8))
    }

    @Test
    fun `insert preserves bytes outside the edit and adds no trailing newline`() {
        val file = write("f.txt", "a\nb\nc".toByteArray(Charsets.UTF_8))
        ok("""{"command":"insert","path":"${file.absolutePath}","insert_line":1,"new_str":"X"}""")
        assertTrue(file.readBytes().contentEquals("a\nX\nb\nc".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `insert on a missing file is FS_NOT_FOUND`() {
        val outcome = err(
            """{"command":"insert","path":"${File(tmp, "nope.txt").absolutePath}","insert_line":0,"new_str":"X"}""",
        )
        assertEquals(ToolErrorCode.FS_NOT_FOUND, outcome.code)
    }

    @Test
    fun `insert without new_str is BAD_ARGUMENT`() {
        val file = write("f.txt", "a\n")
        val outcome = err("""{"command":"insert","path":"${file.absolutePath}","insert_line":0}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    @Test
    fun `insert without insert_line is BAD_ARGUMENT`() {
        val file = write("f.txt", "a\n")
        val outcome = err("""{"command":"insert","path":"${file.absolutePath}","new_str":"X"}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    @Test
    fun `insert beyond the last line is BAD_ARGUMENT`() {
        val file = write("f.txt", "a\nb\n")
        val outcome =
            err("""{"command":"insert","path":"${file.absolutePath}","insert_line":9,"new_str":"X"}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    // ------------------------------------------------------------------ argument validation

    @Test
    fun `invalid JSON is BAD_ARGUMENT`() {
        val outcome = err("{not json")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
    }

    @Test
    fun `missing command is BAD_ARGUMENT naming the field`() {
        val outcome = err("""{"path":"/tmp/x"}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
        assertTrue(outcome.message.contains("command"), outcome.message)
    }

    @Test
    fun `missing path is BAD_ARGUMENT naming the field`() {
        val outcome = err("""{"command":"view"}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
        assertTrue(outcome.message.contains("path"), outcome.message)
    }

    @Test
    fun `a command outside the enum is BAD_ARGUMENT`() {
        val outcome = err("""{"command":"delete","path":"/tmp/x"}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
        assertTrue(outcome.message.contains("delete"), outcome.message)
    }

    @Test
    fun `a relative path is BAD_ARGUMENT with the teaching message`() {
        val outcome = err("""{"command":"view","path":"some/file.txt"}""")
        assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
        assertEquals(
            "The path some/file.txt is not an absolute path, it should start with `/`. " +
                "Maybe you meant /some/file.txt?",
            outcome.message,
        )
    }
}
