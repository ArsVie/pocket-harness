package com.arsvie.pocketharness.platform

import android.content.Context
import android.os.Build
import ph.ports.ShellBinaries
import java.io.File

/** Which shell the harness is actually running, decided by an in-process self-test. */
enum class ShellKind {
    /** The bundled GNU bash (ADR-006), unpacked from assets to `files/userland/bash`. */
    BASH,

    /** The platform's own bionic shell (`/system/bin/sh`, mksh); `sh -c`. The fallback. */
    PLATFORM,
}

/**
 * Where the shipped userland actually lives on this device (ADR-006; ADR-001's open question
 * "which userland ships" was settled by running it — ENVIRONMENT.md):
 *
 *   1. [bash] GNU bash 5.3, built with the NDK against bionic for this device's ABI
 *      (`x86_64` / `arm64-v8a`), shipped as an asset, unpacked to `files/userland/bash`, exec'd
 *      from app data. Measured in-app: it runs under the app seccomp filter that kills a static
 *      musl busybox (SIGSYS), so it is the preferred shell.
 *   2. [systemShell] `/system/bin/sh` — the platform mksh. Always present; the fallback when the
 *      bundled bash cannot start on some future device.
 *
 * Layout under `context.filesDir`:
 *   userland/bash      the bundled bash for this ABI, unpacked from `assets/userland/bash-<abi>`
 *   shims/rm           the `rm`→trash shim from `assets/userland/rm.sh` (shebang in-file)
 *   shims/bash         a one-line exec shim to the bundled bash (generated at provision;
 *                      Android has no `bash` on PATH and scripts expect one)
 *
 * Everything here is a path, not a policy: `:core` never guesses a path (ShellBinaries).
 */
class AndroidShellBinaries(context: Context) : ShellBinaries {

    private val userlandDir: File = File(context.filesDir, "userland")

    /** The bundled bash for this device's ABI; unpacked by [Userland.provision]. */
    val bash: File = File(userlandDir, "bash")

    /** The platform shell — the fallback when the bundled bash cannot be proven. */
    val systemShell: File = File("/system/bin/sh")

    /** Directory holding the shipped shims (`rm`→trash, `bash`→bundled bash). */
    val shimDir: File = File(context.filesDir, "shims")

    /** The `rm` shim itself. */
    val shim: File = File(shimDir, "rm")

    /** The `bash` shim: re-execs the resolved bundled bash; written by [Userland.provision]. */
    val bashShim: File = File(shimDir, "bash")

    /**
     * The bash asset for this device, picked in [Build.SUPPORTED_ABIS] order so the emulator takes
     * the x86_64 build and a phone takes arm64-v8a. Null on an ABI with no shipped build.
     */
    val bashAsset: String? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> ABI_ASSETS[abi] }

    /** Set by [resolve] after the self-test; the platform shell until proven otherwise. */
    var kind: ShellKind = ShellKind.PLATFORM
        private set
    var chosen: File = systemShell
        private set

    private var resolved = false
    private var resolveLog = ""

    /**
     * Force resolution before any path is handed out. This exists because a caller *did* forget:
     * `AppGraph` built one instance and `Userland.provision` resolved a different one, so the shell
     * kept the unresolved default (which was not even shipped) and every command died with ENOENT.
     * Making the accessors resolve means there is no way to hold an unresolved instance and read a
     * path off it.
     */
    private fun ensureResolved() {
        if (!resolved) resolve()
    }

    override fun shellPath(): String {
        ensureResolved()
        return chosen.absolutePath
    }

    /**
     * PATH: the `rm` shim dir FIRST, then `/system/bin` (toybox applets) — for both shells; the
     * bundled bash has no applet dir of its own, it is a plain interpreter.
     */
    override fun pathPrefix(): String {
        ensureResolved()
        return shimDir.absolutePath + File.pathSeparator + SYSTEM_BIN
    }

    /** argv for running `command` under the chosen shell: `[shell, -c, command]` in both cases. */
    fun argv(command: String): List<String> {
        ensureResolved()
        return listOf(chosen.absolutePath, "-c", command)
    }

    /**
     * Run the cheap self-test that decides which shell can actually work in this process: a
     * command-carrying invocation that must return exit 0 with non-empty stdout. Bash is preferred;
     * the platform shell is tried when bash cannot start, so a broken bash never strands the app.
     */
    fun resolve(): String {
        if (resolved) return resolveLog

        fun works(argv: List<String>): Boolean = try {
            val r = Userland.execDirect(argv, env = mapOf("PATH" to SYSTEM_BIN))
            r.exitCode == 0 && r.out.isNotBlank()
        } catch (t: Throwable) {
            false
        }

        if (bash.exists() && works(listOf(bash.absolutePath, "-c", "echo selftest"))) {
            kind = ShellKind.BASH
            chosen = bash
            resolveLog = "shell self-test: bundled bash works -> BASH (${bash.absolutePath})"
        } else if (systemShell.exists() && works(listOf(systemShell.absolutePath, "-c", "echo selftest"))) {
            kind = ShellKind.PLATFORM
            chosen = systemShell
            resolveLog = "shell self-test: bundled bash did NOT work, /system/bin/sh works -> PLATFORM"
        } else {
            kind = ShellKind.PLATFORM
            chosen = systemShell
            resolveLog = "shell self-test: neither bash nor the platform shell passed; falling back to /system/bin/sh"
        }
        resolved = true
        return resolveLog
    }

    private companion object {
        const val SYSTEM_BIN = "/system/bin"

        /** Asset per ABI; the shell binary is not a native-lib — it is exec'd from app data. */
        val ABI_ASSETS = mapOf(
            "x86_64" to "userland/bash-x86_64",
            "arm64-v8a" to "userland/bash-aarch64",
        )
    }
}

object Userland {

    const val TAG = "PocketHarness"

    /**
     * Unpack the bundled bash for this device's ABI into app-private storage, make it executable,
     * resolve which shell works, then write the shims (`rm` from its asset, `bash` generated).
     * Idempotent; safe to call
     * on every start. Returns a human-readable log line per step (the exec probe records it).
     */
    fun provision(context: Context): List<String> {
        val bin = AndroidShellBinaries(context)
        val log = mutableListOf<String>()

        // v2 (ADR-006): the busybox userland was retired after the app seccomp filter was measured
        // to kill static musl applets (SIGSYS). Remove anything it left behind on upgrade.
        val legacyRoot = bin.bash.parentFile
        if (legacyRoot != null) {
            listOf(File(legacyRoot, "busybox"), File(legacyRoot, "bin")).forEach { leftover ->
                if (leftover.exists()) {
                    leftover.deleteRecursively()
                    log += "removed legacy busybox artifact ${leftover.absolutePath}"
                }
            }
        }

        // 1. The bash asset for this ABI -> files/userland/bash
        val asset = bin.bashAsset
        if (asset == null) {
            log += "no bundled bash for abis=${Build.SUPPORTED_ABIS.joinToString(",")}: the platform shell will be used"
        } else {
            legacyRoot?.mkdirs()
            if (!bin.bash.exists() || bin.bash.length() == 0L) {
                context.assets.open(asset).use { input ->
                    bin.bash.outputStream().use { input.copyTo(it) }
                }
                log += "unpacked assets/$asset -> ${bin.bash.absolutePath} (${bin.bash.length()} bytes)"
            } else {
                log += "bash already unpacked at ${bin.bash.absolutePath} (${bin.bash.length()} bytes)"
            }
            bin.bash.setExecutable(true, true)
            log += "chmod +x ${bin.bash.absolutePath} (canExecute=${bin.bash.canExecute()})"
        }

        // 2. Which shell actually works in this process? (evidence: ExecProbe / exec-probe.txt)
        log += bin.resolve()

        // 3. The rm shim ships complete (fixed /system/bin/sh shebang; no substitution step).
        bin.shimDir.mkdirs()
        val shimText = context.assets.open("userland/rm.sh").use { it.bufferedReader().readText() }
        bin.shim.writeText(shimText)
        bin.shim.setExecutable(true, true)
        log += "wrote shim ${bin.shim.absolutePath} (canExecute=${bin.shim.canExecute()}, " +
            "shebang=${shimText.lineSequence().first()})"

        // 4. The bash shim (shell-spike finding): with no `bash` on PATH, `bash script.sh`
        // and `#!/system/bin/env bash` shebangs die with ENOENT. A one-line exec shim restores
        // both; written only when the self-test proved the bundled bash actually works.
        if (bin.kind == ShellKind.BASH) {
            bin.bashShim.writeText("#!/system/bin/sh\nexec \"${bin.chosen.absolutePath}\" \"\$@\"\n")
            bin.bashShim.setExecutable(true, true)
            log += "wrote shim ${bin.bashShim.absolutePath} (canExecute=${bin.bashShim.canExecute()}, " +
                "exec=${bin.chosen.absolutePath})"
        } else {
            log += "no bash shim: bundled bash did not resolve to BASH; platform shell in use"
        }

        log += "PATH prefix = ${bin.pathPrefix()}"
        return log
    }

    /** Resolve (and cache) which shell works, for callers that need argv/paths only. */
    fun resolveShell(context: Context): AndroidShellBinaries =
        AndroidShellBinaries(context).also { it.resolve() }

    data class DirectResult(val out: String, val err: String, val exitCode: Int)

    /** A single blocking exec with stdout/stderr captured separately; used for provisioning only. */
    fun execDirect(
        argv: List<String>,
        cwd: File? = null,
        env: Map<String, String> = emptyMap(),
    ): DirectResult {
        val pb = ProcessBuilder(argv).redirectErrorStream(false)
        if (cwd != null) pb.directory(cwd)
        env.forEach { (k, v) -> pb.environment()[k] = v }
        val proc = pb.start()
        proc.outputStream.close()
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread { proc.inputStream.bufferedReader().use { out.append(it.readText()) } }
        val t2 = Thread { proc.errorStream.bufferedReader().use { err.append(it.readText()) } }
        t1.start(); t2.start()
        val code = proc.waitFor()
        t1.join(); t2.join()
        return DirectResult(out.toString(), err.toString(), code)
    }

    fun execDirect(vararg argv: String): DirectResult = execDirect(argv.toList())
}
