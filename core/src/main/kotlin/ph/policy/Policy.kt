package ph.policy

/**
 * ADR-004. Two modes, presented as one switch. The floor (see [PolicyFloor]) sits above both, so
 * the switch can never relax the never-allowed set.
 */
enum class ExecutionMode { DEFAULT, YOLO }

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data class Deny(val rule: String, val reason: String) : PolicyDecision
}

/**
 * The never-allowed floor. Content is data, not code (ADR-002 / Rec 11); this interface is only the
 * check. Called for every `bash` command in both modes.
 */
fun interface PolicyFloor {
    fun check(command: String): PolicyDecision
}

/** Per-folder trust in DEFAULT mode (ADR-004 §2). */
interface TrustStore {
    fun isTrusted(cwd: String): Boolean
    fun trust(cwd: String)
    fun revoke(cwd: String)
}

/**
 * `rm` → trash (ADR-004 §5). The shim is a script shipped with the userland; this is the contract
 * the tool layer relies on when it prepares the environment for a command.
 */
interface TrashPolicy {
    fun trashDir(cwd: String): String

    /** Directory holding the shipped `rm` shim; prepended to PATH, ahead of the platform applets. */
    fun shimDir(): String
}
