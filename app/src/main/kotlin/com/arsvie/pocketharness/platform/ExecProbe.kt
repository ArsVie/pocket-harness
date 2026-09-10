package com.arsvie.pocketharness.platform

import android.content.Context
import android.util.Log
import ph.prompt.Budgets
import java.io.File

/**
 * Wave-0 in-app exec proof. Unpacks the shipped userland, exec's candidates **inside the app
 * process**, and writes everything it observed — argv, exit code (with decoded signal),
 * stdout, stderr, exceptions — to `<filesDir>/exec-probe.txt` so the orchestrator can read it
 * back with `run-as`.
 *
 * Two experiments, in order (ADR-001 / ENVIRONMENT.md "W^X"):
 *   A. the shipped static busybox, packaged as `jniLibs/arm64-v8a/libbusybox.so` and exec'd from
 *      the APK's native library dir instead of app data.
 *   B. the platform shell (`/system/bin/sh`, toybox), including a copy exec'd from app data and a
 *      `#!/system/bin/sh` script from app data — the shape the `rm` shim takes.
 *
 * A row is only "working" if it is an **applet-carrying** invocation (not `--help`) returning
 * exit 0 with non-empty stdout. `--help` prints usage before applet dispatch, so it proves nothing.
 */
object ExecProbe {

    private const val TAG = "PocketHarness"

    val probeFileName = "exec-probe.txt"

    fun run(context: Context) {
        val report = StringBuilder()
        val probeFile = File(context.filesDir, probeFileName)
        val bin = AndroidShellBinaries(context)
        val budgets = Budgets()

        fun line(s: String) {
            Log.i(TAG, s)
            report.append(s).append('\n')
        }

        line("PocketHarness in-app exec probe")
        line("filesDir              = ${context.filesDir.absolutePath}")
        line("nativeLibraryDir      = ${context.applicationInfo.nativeLibraryDir}")
        line("commandTimeout (Budgets.commandTimeoutMs) = ${budgets.commandTimeoutMs}")

        val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
        val trash = File(workspace, ".trash")

        try {
            Userland.provision(context).forEach { line("provision: $it") }
        } catch (t: Throwable) {
            line("PROVISION FAILED: ${t.javaClass.name}: ${t.message}")
            probeFile.writeText(report.toString())
            return
        }

        val nativeBusybox = bin.nativeBinary
        line("")
        line("--- packaged native library (Experiment A candidate) ---")
        line(
            "libbusybox.so exists=${nativeBusybox.exists()} length=${nativeBusybox.length()} " +
                "canExecute=${nativeBusybox.canExecute()} path=${nativeBusybox.absolutePath}",
        )
        line("asset busybox  exists=${bin.binary.exists()} length=${bin.binary.length()} path=${bin.binary.absolutePath}")

        // ── helpers ─────────────────────────────────────────────────────────────────────────
        fun row(label: String, argv: List<String>, env: Map<String, String> = emptyMap(), cwd: File = workspace) {
            val r = try {
                Userland.execDirect(argv, cwd = cwd, env = env)
            } catch (t: Throwable) {
                Userland.DirectResult("", "${t.javaClass.name}: ${t.message}", -1)
            }
            val sig = if (r.exitCode in 129..192) " (signal ${r.exitCode - 128})" else ""
            line(
                "[$label] argv=" + argv.joinToString(" ") + " -> exit=" + r.exitCode + sig +
                    " out=<" + r.out.trim().take(400) + "> err=<" + r.err.trim().take(300) + ">",
            )
            r
        }

        // ── Experiment A: busybox from the APK native library dir ───────────────────────────
        line("")
        line("=== EXPERIMENT A: static busybox from nativeLibraryDir ===")
        if (nativeBusybox.exists()) {
            val aEnv = mapOf("PATH" to bin.pathPrefix())
            row("A control: libbusybox --help (NOT proof)", listOf(nativeBusybox.absolutePath, "--help"), aEnv)
            row("A applet: libbusybox echo", listOf(nativeBusybox.absolutePath, "echo", "A_ECHO_OK"), aEnv)
            row("A applet: libbusybox true", listOf(nativeBusybox.absolutePath, "true"), aEnv)
            row("A shell: libbusybox sh -c echo", listOf(nativeBusybox.absolutePath, "sh", "-c", "echo A_ASH_OK; uname -a"), aEnv)
        } else {
            line("[A] nativeLibraryDir has no libbusybox.so -> Experiment A cannot run")
        }

        // ── Experiment B: the platform shell ────────────────────────────────────────────────
        line("")
        line("=== EXPERIMENT B: platform shell (bionic) ===")
        val systemEnv = mapOf("PATH" to "/system/bin")
        row("B system sh -c + seccomp of the child",
            listOf("/system/bin/sh", "-c", "echo B_SYSTEM_SH_OK; grep -E '^Seccomp' /proc/self/status; id -u"),
            systemEnv)
        row("B toybox echo applet", listOf("/system/bin/toybox", "echo", "B_TOYBOX_OK"), systemEnv)
        row("B toybox applet by name (/system/bin/echo)", listOf("/system/bin/echo", "B_TOYBOX_BYNAME_OK"), systemEnv)
        row("B grep applet (/system/bin/grep)", listOf("/system/bin/grep", "Seccomp", "/proc/self/status"), systemEnv)

        // a COPY of /system/bin/sh exec'd from app data (same shape the busybox attempt used)
        val probeDir = File(context.filesDir, "probe-shell").apply { mkdirs() }
        val copiedSh = File(probeDir, "sh")
        try {
            File("/system/bin/sh").inputStream().use { input -> copiedSh.outputStream().use { input.copyTo(it) } }
            copiedSh.setExecutable(true, true)
            row("B copied bionic /system/bin/sh from app data",
                listOf(copiedSh.absolutePath, "-c", "echo B_COPIED_SH_OK"), systemEnv)
        } catch (t: Throwable) {
            line("[B copied bionic /system/bin/sh from app data] THREW ${t.javaClass.name}: ${t.message}")
        }

        // a script in app data with a platform shebang — the shape the rm shim takes
        val script = File(probeDir, "hello.sh")
        try {
            script.writeText("#!/system/bin/sh\necho B_SHEBANG_SCRIPT_OK\nexit 0\n")
            script.setExecutable(true, true)
            row("B script with #!/system/bin/sh shebang from app data", listOf(script.absolutePath), systemEnv)
        } catch (t: Throwable) {
            line("[B script with #!/system/bin/sh shebang from app data] THREW ${t.javaClass.name}: ${t.message}")
        }

        // ── the full command the harness runs, through the PROVEN shell ─────────────────────
        val command = buildString {
            append("echo == app shell smoke ==\n")
            append("uname -a 2>/dev/null || echo uname-missing\n")
            append("echo whoami-id: \$(id -u 2>/dev/null || echo unknown)\n")
            append("mkdir -p work\n")
            append("echo keep-me > work/keep.txt\n")
            append("echo delete-me > work/doomed.txt\n")
            append("echo before-rm: \$(ls work)\n")
            append("rm work/doomed.txt\n")
            append("echo after-rm: \$(ls work)\n")
            append("echo --- trash ---\n")
            append("ls -a .trash 2>/dev/null || echo no-trash\n")
            append("echo this line goes to stderr >&2\n")
            append("exit 7\n")
        }
        line("")
        line("=== app shell smoke (proven shell) ===")
        val chosen = Userland.resolveShell(context)
        line("chosen shell = ${chosen.chosen.absolutePath} (kind=${chosen.kind})")
        val smokeArgv = chosen.argv("$command")
        line("smoke argv  = ${smokeArgv.joinToString(" ")}")
        val smoke = try {
            Userland.execDirect(
                smokeArgv,
                cwd = workspace,
                env = mapOf("PATH" to chosen.pathPrefix, "PH_TRASH_DIR" to trash.absolutePath),
            )
        } catch (t: Throwable) {
            Userland.DirectResult("", "${t.javaClass.name}: ${t.message}", -1)
        }
        val sig = if (smoke.exitCode in 129..192) " (signal ${smoke.exitCode - 128})" else ""
        line("[smoke] exit=${smoke.exitCode}$sig")
        line("--- smoke stdout ---")
        line(smoke.out.trimEnd())
        line("--- smoke stderr ---")
        line(smoke.err.trimEnd())
        line("--- observable effects ---")
        line("work/keep.txt exists   = ${File(File(workspace, "work"), "keep.txt").exists()}")
        line("work/doomed.txt exists = ${File(File(workspace, "work"), "doomed.txt").exists()}")
        line("trash entries          = ${trash.listFiles()?.joinToString { it.name } ?: "<missing>"}")

        probeFile.writeText(report.toString())
        Log.i(TAG, "exec probe written to ${probeFile.absolutePath}")
    }
}
