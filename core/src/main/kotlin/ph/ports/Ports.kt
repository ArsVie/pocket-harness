package ph.ports

/**
 * The real shell on the device. Implemented in `:app` over the shipped userland
 * (see planning/decisions/0001-execution-surface.md); faked in tests.
 */
interface Shell {
    suspend fun exec(
        command: String,
        cwd: String,
        timeoutMs: Long,
        env: Map<String, String> = emptyMap(),
    ): ExecResult
}

data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean,
)

/**
 * Where the userland actually lives on this device. The app decides (native library dir
 * or files dir); `:core` never guesses a path.
 */
interface ShellBinaries {
    /** Absolute path to the shell binary to exec. */
    fun shellPath(): String

    /** PATH prefix holding the `rm` shim first, then the busybox applets. */
    fun pathPrefix(): String
}

/** Key material by reference: the value never appears in a config file or a log. */
interface SecretStore {
    fun get(ref: String): String?
    fun put(ref: String, value: String)
}

interface Clock {
    fun nowMillis(): Long
}
