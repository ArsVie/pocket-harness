package com.arsvie.pocketharness.platform

import android.content.Context
import android.util.Log
import ph.ports.ShellBinaries
import java.io.File

/** Which shell the harness is actually running, decided by an in-process self-test. */
enum class ShellKind {
    /** The shipped static busybox, exec'd from the APK native library dir or app data; `busybox sh -c`. */
    BUSYBOX,

    /** The platform's own bionic shell; `sh -c`. Runs under the app's seccomp filter by construction. */
    PLATFORM,
}

/**
 * Where the shipped userland actually lives on this device (ADR-001 / ADR-005 §8).
 *
 * Candidates, in preference order, with the evidence that picks between them recorded in
 * `files/exec-probe.txt` (ExecProbe):
 *   1. [nativeBinary] `nativeLibraryDir/libbusybox.so` — the static busybox packaged as a native
 *      library so it is exec'd from `/data/app/~~*/<pkg>*/lib/arm64/`, not from app data.
 *   2. [systemShell] `/system/bin/sh` — the platform's bionic mksh. Android's own tools are built
 *      for the app seccomp filter, so this is the candidate userland if (1) cannot dispatch applets.
 *   3. [binary] the asset-unpacked busybox at `files/userland/busybox` — retained as the fallback
 *      for a build/device where neither packaged path is present.
 *
 * Layout under `context.filesDir`:
 *   userland/busybox   the static aarch64 busybox, unpacked from `assets/userland/busybox`, +x
 *   userland/bin/      applet symlinks (`busybox --install -s`) for the busybox PATH
 *   shims/rm           the `rm`→trash shim from `assets/userland/rm.sh`, shebang + `@SHELL@`
 *                      rewritten for whichever shell [resolve] proves works (ADR-004 §5)
 *
 * Everything here is a path, not a policy: `:core` never guesses a path (ShellBinaries).
 */
class AndroidShellBinaries(context: Context) : ShellBinaries {

    private val userlandDir: File = File(context.filesDir, "userland")

    /** The static busybox as unpacked from assets — candidate 3 (fallback). */
    val binary: File = File(userlandDir, "busybox")

    /** The same busybox packaged as a native library (candidate 1). */
    val nativeBinary: File = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")

    /** The platform shell (candidate 2). */
    val systemShell: File = File("/system/bin/sh")

    /** The busybox applet dir (symlinks installed by [Userland.provision]). */
    val appletDir: File = File(userlandDir, "bin")

    /** Directory holding the shipped `rm` shim. */
    val shimDir: File = File(context.filesDir, "shims")

    /** The shim itself. */
    val shim: File = File(shimDir, "rm")

    /** Set by [resolve] after the self-test; defaults to the packaged native busybox. */
    var kind: ShellKind = ShellKind.BUSYBOX
        private set
    var chosen: File = nativeBinary
        private set

    override fun shellPath(): String = chosen.absolutePath

    /**
     * PATH: the `rm` shim dir FIRST, then the shell's own applet dir. For the platform shell the
     * applet dir is `/system/bin`; for busybox it is the symlink farm in app data.
     */
    override fun pathPrefix(): String =
        shimDir.absolutePath + File.pathSeparator +
            if (kind == ShellKind.BUSYBOX) appletDir.absolutePath else "/system/bin"

    /** argv[0] + args for running `command` under the chosen shell. */
    fun argv(command: String): List<String> =
        if (kind == ShellKind.BUSYBOX) listOf(chosen.absolutePath, "sh", "-c", command)
        else listOf(chosen.absolutePath, "-c", command)

    /**
     * Run the cheap self-test that decides which shell can actually dispatch work in this process:
     * an applet-carrying invocation (`echo`) that must return exit 0 with non-empty stdout. `--help`
     * is explicitly *not* accepted as proof: it prints usage before applet dispatch.
     */
    fun resolve(): String {
        fun works(argv: List<String>): Boolean = try {
            val r = Userland.execDirect(argv, env = mapOf("PATH" to "/system/bin"))
            r.exitCode == 0 && r.out.isNotBlank()
        } catch (t: Throwable) {
            false
        }

        if (nativeBinary.exists() && works(listOf(nativeBinary.absolutePath, "echo", "selftest"))) {
            kind = ShellKind.BUSYBOX
            chosen = nativeBinary
            return "shell self-test: nativeLibraryDir/libbusybox.so dispatches applets -> BUSYBOX"
        }
        if (systemShell.exists() && works(listOf(systemShell.absolutePath, "-c", "echo selftest"))) {
            kind = ShellKind.PLATFORM
            chosen = systemShell
            return "shell self-test: native busybox did NOT dispatch, /system/bin/sh works -> PLATFORM"
        }
        kind = ShellKind.BUSYBOX
        chosen = binary
        return "shell self-test: neither packaged path worked; falling back to asset busybox ${binary.absolutePath}"
    }
}

object Userland {

    const val TAG = "PocketHarness"

    /** The `rm` shim's shebang line for a given shell. Android has no `/bin/sh`. */
    fun shimShebang(kind: ShellKind, shellPath: String): String =
        if (kind == ShellKind.BUSYBOX) "#!" + shellPath + " sh" else "#!/system/bin/sh"

    /**
     * Unpack the shipped busybox + `rm` shim into app-private storage, make both executable, resolve
     * which shell works, then write the shim with the shebang that shell can actually execute.
     * Idempotent; safe to call on every start. Returns a human-readable log line per step.
     */
    fun provision(context: Context): List<String> {
        val bin = AndroidShellBinaries(context)
        val log = mutableListOf<String>()

        // Fallback first: the asset busybox is always unpacked, whichever shell wins. It is what a
        // future build/device without the native library or platform shell falls back to.
        bin.binary.parentFile?.mkdirs()
        if (!bin.binary.exists() || bin.binary.length() == 0L) {
            context.assets.open("userland/busybox").use { input ->
                bin.binary.outputStream().use { input.copyTo(it) }
            }
            log += "unpacked assets/userland/busybox -> ${bin.binary.absolutePath} (${bin.binary.length()} bytes)"
        } else {
            log += "busybox already unpacked at ${bin.binary.absolutePath} (${bin.binary.length()} bytes)"
        }
        bin.binary.setExecutable(true, true)
        log += "chmod +x ${bin.binary.absolutePath} (canExecute=${bin.binary.canExecute()})"

        // Which shell actually works in this process? (evidence: ExecProbe / exec-probe.txt)
        log += bin.resolve()

        // Applet symlinks so PATH resolves `sh`, `mv`, `date`, `basename`, `mkdir`, `ls`, `uname`…
        // Only meaningful for the busybox shell; on the platform shell PATH is /system/bin.
        if (!File(bin.appletDir, "sh").exists()) {
            bin.appletDir.mkdirs()
            val r = execDirect(bin.binary.absolutePath, "--install", "-s", bin.appletDir.absolutePath)
            log += "busybox --install -s ${bin.appletDir.absolutePath} -> exit ${r.exitCode}; stderr=${r.err.ifBlank { "<empty>" }}"
        } else {
            log += "applet dir already installed at ${bin.appletDir.absolutePath}"
        }

        // The rm shim: @SHELL@ is replaced with the absolute shell path, and the shebang is rewritten
        // to match the shell that was proven to work. A shim whose interpreter traps with SIGSYS (or
        // ENOENT, since Android has no /bin/sh) is useless, so this must follow [bin.resolve].
        bin.shimDir.mkdirs()
        val shimText = context.assets.open("userland/rm.sh").use { it.bufferedReader().readText() }
            .replaceFirst("#!/bin/sh", shimShebang(bin.kind, bin.shellPath()))
            .replace("@SHELL@", bin.shellPath())
        bin.shim.writeText(shimText)
        bin.shim.setExecutable(true, true)
        log += "wrote shim ${bin.shim.absolutePath} (canExecute=${bin.shim.canExecute()}, " +
            "shebang=${shimText.lineSequence().first()})"

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
