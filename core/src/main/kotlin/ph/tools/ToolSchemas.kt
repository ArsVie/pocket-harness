package ph.tools

import ph.model.ToolSchema

/**
 * The byte-stable tool catalog. OWNED BY THE ORCHESTRATOR: this file is the request prefix, and a
 * prefix that varies per turn costs cache reads (ADR-003, Pattern 11). Authored once; no workstream
 * edits it, and no description is built by string interpolation from runtime state.
 *
 * Both schemas are the reference preset's, with the field order preserved exactly as SPEC §2.4 lists
 * it. The `bash` description is the one place device-specific guidance belongs — the persona is a
 * single sentence on purpose (see `app/src/main/assets/presets/minimal.yaml` and research 02 §10.2),
 * so this prose is what teaches the model how to work on a phone.
 */
object ToolSchemas {

    const val BASH_NAME = "bash"
    const val EDITOR_NAME = "str_replace_editor"

    /**
     * The reference's 7-bullet shape, corrected for the device: the reference advertises a Linux
     * package mirror over apt/pip, which is factually wrong here, and adds the two instructions the
     * paper's build order calls for (verify before claiming, report failure honestly).
     */
    val BASH_DESCRIPTION: String = listOf(
        "Run commands in a bash shell",
        "* State is persistent across command calls and discussions with the user.",
        "* Commands run in this session's working directory, and relative paths resolve there. Run " +
            "'pwd' to confirm where you are.",
        "* You don't have access to the internet via this tool, and there is no package manager: " +
            "apt, pip, npm and similar are not available. Use what the shell already provides.",
        "* The shell is GNU bash 5.3 on Android, with the toybox utilities; most common tools are " +
            "present, but some GNU options and tools are not. Check a flag with --help if you are " +
            "unsure rather than assuming GNU behaviour.",
        "* To inspect a particular line range of a file, e.g. lines 10-25, try 'sed -n 10,25p " +
            "/path/to/the/file'. Check the size with 'wc -l' before dumping a whole file.",
        "* To search, use 'grep -rn pattern path'. There is no separate search tool.",
        "* Please avoid commands that may produce a very large amount of output; long output is " +
            "truncated and the rest is written to a file you can read in ranges.",
        "* Please run long lived commands in the background, e.g. 'sleep 10 &' or start a server in " +
            "the background.",
        "* 'rm' moves files to a .trash/ directory instead of deleting them.",
        "* Run the thing before saying it works, and report a failure as a failure rather than " +
            "describing what you intended.",
    ).joinToString("\n")

    val EDITOR_DESCRIPTION: String = listOf(
        "Custom editing tool for viewing, creating and editing files.",
        "* State is persistent across command calls and discussions with the user.",
        "* 'view' shows a numbered listing of a file, or a directory up to 2 levels deep.",
        "* 'create' writes a new file and refuses to overwrite an existing one.",
        "* 'str_replace' replaces a unique occurrence of old_str with new_str; if old_str is not " +
            "unique the call fails and names the conflicting lines.",
        "* 'insert' inserts new_str after the given line, with 0 meaning the start of the file.",
        "* Paths must be absolute.",
    ).joinToString("\n")

    /** Field order is part of the contract; `command` then `path`, exactly as SPEC §2.4 lists them. */
    const val BASH_PARAMETERS_JSON: String =
        """{"type":"object","properties":{"command":{"type":"string","description":"The bash command to run. Relative path is preferred in the command."}},"required":["command"]}"""

    const val EDITOR_PARAMETERS_JSON: String =
        """{"type":"object","properties":{"command":{"type":"string","description":"The commands to run. Allowed options are: `view`, `create`, `str_replace`, `insert`.","enum":["view","create","str_replace","insert"]},"path":{"type":"string","description":"Absolute path to file or directory, e.g. `/repo/file.py` or `/repo`."},"file_text":{"type":"string","description":"Required parameter of `create` command, with the content of the file to be created."},"insert_line":{"type":"integer","description":"Required parameter of `insert` command. The `new_str` will be inserted AFTER the line `insert_line` of `path`."},"new_str":{"type":"string","description":"Optional parameter of `str_replace` command containing the new string (if not given, no string will be added). Required parameter of `insert` command containing the string to insert."},"old_str":{"type":"string","description":"Required parameter of `str_replace` command containing the string in `path` to replace."},"view_range":{"type":"array","description":"Optional parameter of `view` command when `path` points to a file. If none is given, the full file is shown. If provided, the file will be shown in the indicated line number range, e.g. [11, 12] will show lines 11 and 12. Indexing at 1 to start. Setting `[start_line, -1]` shows all lines from `start_line` to the end of the file.","items":{"type":"integer"}}},"required":["command","path"]}"""

    val BASH: ToolSchema = ToolSchema(
        name = BASH_NAME,
        description = BASH_DESCRIPTION,
        parametersJson = BASH_PARAMETERS_JSON,
    )

    val STR_REPLACE_EDITOR: ToolSchema = ToolSchema(
        name = EDITOR_NAME,
        description = EDITOR_DESCRIPTION,
        parametersJson = EDITOR_PARAMETERS_JSON,
    )

    /** Preset order (`minimal.yaml`): bash, then the editor. Never reorder. */
    fun all(): List<ToolSchema> = listOf(BASH, STR_REPLACE_EDITOR)

    fun byName(name: String): ToolSchema? = all().firstOrNull { it.name == name }
}
