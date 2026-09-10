package ph.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import ph.model.ToolCallRequest
import ph.model.ToolSchema
import ph.prompt.Budgets
import ph.tools.ToolErrorCode.BAD_ARGUMENT
import ph.tools.ToolErrorCode.FS_AMBIGUOUS_EDIT
import ph.tools.ToolErrorCode.FS_EDIT_NOT_FOUND
import ph.tools.ToolErrorCode.FS_EXISTS
import ph.tools.ToolErrorCode.FS_NOT_FOUND
import java.io.File
import java.util.Locale

/**
 * `str_replace_editor` (SPEC §2.4, ADR-002/ADR-004). View/create/str_replace/insert over
 * `java.io.File` only — no Android APIs live in `:core`.
 *
 * Arguments arrive as a raw JSON string on `ToolCallRequest.argumentsJson` and are parsed by hand, so
 * every malformed input becomes an `Err(BAD_ARGUMENT, …)` value rather than an exception. No literal
 * tunables: the only cap this tool needs is the directory-listing line cap, which comes from
 * `Budgets.maxOutputLines`. The 6-wide line-number gutter and the 2-level directory walk are reference
 * *contract* (SPEC §2.4), not tunables, and are named constants below.
 */
class StrReplaceEditorTool(private val budgets: Budgets) : Tool {

    override val schema: ToolSchema = ToolSchemas.STR_REPLACE_EDITOR

    override suspend fun run(call: ToolCallRequest, ctx: ToolContext): ToolOutcome =
        execute(call.argumentsJson)

    internal fun execute(argumentsJson: String): ToolOutcome {
        val args = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (e: Exception) {
            return err(BAD_ARGUMENT, "Invalid JSON arguments: ${e.message}")
        }

        val commandName = args.stringOrNull("command")
            ?: return err(BAD_ARGUMENT, "Missing required field: `command`.")
        val command = Command.from(commandName)
            ?: return err(
                BAD_ARGUMENT,
                "Unknown `command`: `$commandName`. Allowed options are: " +
                    Command.entries.joinToString(", ") { "`${it.wire}`" } + ".",
            )

        val path = args.stringOrNull("path")
            ?: return err(BAD_ARGUMENT, "Missing required field: `path`.")
        if (!path.startsWith("/")) {
            return err(
                BAD_ARGUMENT,
                "The path $path is not an absolute path, it should start with `/`. Maybe you meant /$path?",
            )
        }

        return when (command) {
            Command.VIEW -> view(path, args)
            Command.CREATE -> create(path, args)
            Command.STR_REPLACE -> strReplace(path, args)
            Command.INSERT -> insert(path, args)
        }
    }

    // ------------------------------------------------------------------ view

    private fun view(path: String, args: JsonObject): ToolOutcome {
        val file = File(path)
        if (!file.exists()) return err(FS_NOT_FOUND, "File not found: $path")
        if (file.isDirectory) return viewDirectory(path, file)

        val all = linesOf(readText(file))
        if (all.isEmpty()) return ToolOutcome.Ok("")

        var start = 1
        var end = all.size
        val rawRange = args["view_range"]
        if (rawRange != null) {
            if (rawRange !is JsonArray || rawRange.size != 2) {
                return err(BAD_ARGUMENT, "Invalid `view_range`: expected an array of two integers, e.g. [11, 12].")
            }
            val first = (rawRange[0] as? JsonPrimitive)?.intOrNull
            val second = (rawRange[1] as? JsonPrimitive)?.intOrNull
            if (first == null || second == null) {
                return err(BAD_ARGUMENT, "Invalid `view_range`: expected an array of two integers, e.g. [11, 12].")
            }
            if (first < 1 || first > all.size) {
                return err(
                    BAD_ARGUMENT,
                    "Invalid `view_range`: start line $first is out of range for a file with ${all.size} lines.",
                )
            }
            if (second == -1) {
                start = first
                end = all.size
            } else {
                if (second < first || second > all.size) {
                    return err(
                        BAD_ARGUMENT,
                        "Invalid `view_range`: $first to $second is out of range for a file with ${all.size} lines.",
                    )
                }
                start = first
                end = second
            }
        }

        val rendered = (start..end).joinToString("\n") { line ->
            String.format(Locale.ROOT, "%${LINE_NUMBER_WIDTH}d\t%s", line, all[line - 1])
        }
        return ToolOutcome.Ok(rendered)
    }

    private fun viewDirectory(path: String, dir: File): ToolOutcome {
        val entries = mutableListOf<String>()
        collectEntries(dir, dir, 1, entries)
        val body = if (entries.isEmpty()) "" else entries.joinToString("\n") + "\n"
        return ToolOutcome.Ok("$path:\n$body")
    }

    private fun collectEntries(root: File, dir: File, depth: Int, out: MutableList<String>) {
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            if (out.size >= budgets.maxOutputLines) return
            if (isExcluded(child.name)) continue
            out.add(child.relativeTo(root).path + if (child.isDirectory) "/" else "")
            if (child.isDirectory && depth < MAX_DIRECTORY_DEPTH) {
                collectEntries(root, child, depth + 1, out)
            }
        }
    }

    private fun isExcluded(name: String): Boolean =
        name.startsWith(".") || name == "node_modules" || name == "__pycache__"

    // ---------------------------------------------------------------- create

    private fun create(path: String, args: JsonObject): ToolOutcome {
        val text = args.stringOrNull("file_text")
            ?: return err(BAD_ARGUMENT, "Missing required field: `file_text` for `create`.")
        val file = File(path)
        if (file.exists()) return err(FS_EXISTS, "File already exists: $path")
        val parent = file.parentFile
        if (parent == null || !parent.isDirectory) {
            return err(FS_NOT_FOUND, "Parent directory does not exist: ${parent?.path ?: path}")
        }
        file.writeBytes(text.toByteArray(Charsets.UTF_8))
        return ToolOutcome.Ok("File created successfully at: $path")
    }

    // ----------------------------------------------------------- str_replace

    private fun strReplace(path: String, args: JsonObject): ToolOutcome {
        val oldStr = args.stringOrNull("old_str")
            ?: return err(BAD_ARGUMENT, "Missing required field: `old_str` for `str_replace`.")
        if (oldStr.isEmpty()) {
            return err(BAD_ARGUMENT, "`old_str` must not be empty for `str_replace`.")
        }
        val file = File(path)
        if (!file.exists()) return err(FS_NOT_FOUND, "File not found: $path")

        val content = readText(file)
        val matches = occurrences(content, oldStr)
        if (matches.isEmpty()) {
            return err(FS_EDIT_NOT_FOUND, "No occurrences of the string were found in $path.")
        }
        if (matches.size > 1) {
            val lines = matches.map { lineNumberAt(content, it) }
            return err(
                FS_AMBIGUOUS_EDIT,
                "Found ${matches.size} occurrences of the string in $path at lines " +
                    "${lines.joinToString(", ")}. The string must be unique; include more surrounding " +
                    "context in `old_str`.",
            )
        }

        val newStr = args.stringOrNull("new_str") ?: ""
        val at = matches[0]
        val updated = content.substring(0, at) + newStr + content.substring(at + oldStr.length)
        file.writeBytes(updated.toByteArray(Charsets.UTF_8))
        return ToolOutcome.Ok("The file $path has been edited successfully.")
    }

    // ---------------------------------------------------------------- insert

    private fun insert(path: String, args: JsonObject): ToolOutcome {
        val insertLine = (args["insert_line"] as? JsonPrimitive)?.intOrNull
            ?: return err(BAD_ARGUMENT, "Missing required field: `insert_line` for `insert`.")
        val newStr = args.stringOrNull("new_str")
            ?: return err(BAD_ARGUMENT, "Missing required field: `new_str` for `insert`.")
        val file = File(path)
        if (!file.exists()) return err(FS_NOT_FOUND, "File not found: $path")

        val content = readText(file)
        val hadTrailingNewline = content.endsWith("\n")
        val lines = linesOf(content).toMutableList()
        if (insertLine < 0 || insertLine > lines.size) {
            return err(
                BAD_ARGUMENT,
                "Invalid `insert_line`: $insertLine is out of range for a file with ${lines.size} lines.",
            )
        }
        lines.add(insertLine, newStr)
        val joined = lines.joinToString("\n") + if (hadTrailingNewline) "\n" else ""
        file.writeBytes(joined.toByteArray(Charsets.UTF_8))
        return ToolOutcome.Ok("The file $path has been edited successfully.")
    }

    // ---------------------------------------------------------------- helpers

    private fun readText(file: File): String = String(file.readBytes(), Charsets.UTF_8)

    /** 1-based logical lines: a trailing newline terminates the last line, it does not open a new one. */
    private fun linesOf(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val split = content.split("\n")
        return if (content.endsWith("\n")) split.dropLast(1) else split
    }

    private fun occurrences(haystack: String, needle: String): List<Int> {
        val found = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            found.add(at)
            from = at + needle.length
        }
        return found
    }

    private fun lineNumberAt(content: String, index: Int): Int {
        var line = 1
        for (i in 0 until index) {
            if (content[i] == '\n') line++
        }
        return line
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = this[key] as? JsonPrimitive ?: return null
        return if (element.isString) element.content else null
    }

    private fun err(code: ToolErrorCode, message: String): ToolOutcome = ToolOutcome.Err(code, message)

    private enum class Command(val wire: String) {
        VIEW("view"),
        CREATE("create"),
        STR_REPLACE("str_replace"),
        INSERT("insert"),
        ;

        companion object {
            fun from(wire: String): Command? = entries.firstOrNull { it.wire == wire }
        }
    }

    private companion object {
        /** SPEC §2.4: `cat -n`-style gutter is 6 wide, right aligned. */
        const val LINE_NUMBER_WIDTH = 6

        /** SPEC §2.4: directory views walk up to two levels deep. */
        const val MAX_DIRECTORY_DEPTH = 2
    }
}
