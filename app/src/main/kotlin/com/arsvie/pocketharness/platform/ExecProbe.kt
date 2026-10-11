package com.arsvie.pocketharness.platform

import android.content.Context
import android.os.Build
import android.util.Log
import ph.prompt.Budgets
import java.io.File

/**
 * In-app exec evidence machine (ADR-006 / ENVIRONMENT.md "v2: the bash userland").
 *
 * Gated: it runs only when `<filesDir>/run-probe` exists — it is reproduction scaffolding, not
 * launch-time work (B-3) — and `exec-probe.txt` is where its observations land for `run-as` reads.
 *
 * Sections:
 *   resolution — `Userland.provision` log and the shell self-test outcome.
 *   B — the platform shell (`/system/bin/sh`, mksh + toybox): the fallback path.
 *   C — the bundled GNU bash at `files/userland/bash`: version + the child's seccomp status,
 *       bash-only features, child exec through PATH, and the `rm` shim through bash.
 *   smoke — the full multi-tool command through the shell [AndroidShellBinaries.resolve] chose.
 */
object ExecProbe {

    private const val TAG = "PocketHarness"

    val probeFileName = "exec-probe.txt"

    /** B-3: the probe is debug scaffolding; it must be asked for. */
    private const val TRIGGER_NAME = "run-probe"

    fun run(context: Context) {
        if (!File(context.filesDir, TRIGGER_NAME).exists()) {
            Log.i(TAG, "exec probe skipped: files/$TRIGGER_NAME not present")
            return
        }

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

        // ── helpers ─────────────────────────────────────────────────────────────────────────
        fun row(
            label: String,
            argv: List<String>,
            env: Map<String, String> = emptyMap(),
            cwd: File = workspace,
        ): Userland.DirectResult {
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
            return r
        }

        // ── resolution ──────────────────────────────────────────────────────────────────────
        line("")
        line("=== shell resolution ===")
        line(bin.resolve())
        line("chosen shell = ${bin.chosen.absolutePath} (kind=${bin.kind})")
        line("abis=${Build.SUPPORTED_ABIS.joinToString(",")}; bash asset for this device: ${bin.bashAsset ?: "<none>"}")

        // ── Experiment B: the platform shell ────────────────────────────────────────────────
        line("")
        line("=== EXPERIMENT B: platform shell (bionic mksh + toybox) ===")
        val systemEnv = mapOf("PATH" to "/system/bin")
        row("B system sh -c + seccomp of the child",
            listOf("/system/bin/sh", "-c", "echo B_SYSTEM_SH_OK; grep -E '^Seccomp' /proc/self/status; id -u"),
            systemEnv)
        row("B toybox echo applet", listOf("/system/bin/toybox", "echo", "B_TOYBOX_OK"), systemEnv)
        row("B toybox applet by name (/system/bin/echo)", listOf("/system/bin/echo", "B_TOYBOX_BYNAME_OK"), systemEnv)

        // a script in app data with the platform shebang — the shape the rm shim takes
        val probeDir = File(context.filesDir, "probe-shell").apply { mkdirs() }
        val script = File(probeDir, "hello.sh")
        try {
            script.writeText("#!/system/bin/sh\necho B_SHEBANG_SCRIPT_OK\nexit 0\n")
            script.setExecutable(true, true)
            row("B script with #!/system/bin/sh shebang from app data", listOf(script.absolutePath), systemEnv)
        } catch (t: Throwable) {
            line("[B shebang script] THREW ${t.javaClass.name}: ${t.message}")
        }

        // ── Experiment C: the bundled GNU bash ──────────────────────────────────────────────
        line("")
        line("=== EXPERIMENT C: bundled GNU bash (files/userland/bash) ===")
        val bash = bin.bash
        if (!bash.exists()) {
            line("[C] no bundled bash at ${bash.absolutePath} -> Experiment C cannot run")
        } else {
            line("[C] candidate = ${bash.absolutePath} (${bash.length()} bytes, canExecute=${bash.canExecute()})")
            val cEnv = mapOf("PATH" to "/system/bin")
            row(
                "C bash version + seccomp",
                listOf(bash.absolutePath, "-c",
                    "echo C_BASH_OK; echo BASH_VERSION=\$BASH_VERSION; grep -E '^Seccomp' /proc/self/status; id -u"),
                cEnv,
            )
            row(
                "C bash features (arrays, cond, pipefail, pipe)",
                listOf(bash.absolutePath, "-c",
                    "a=(one two three); echo ARRAY=\${a[1]}; [[ 5 -gt 3 ]] && echo COND_OK; " +
                        "set -o pipefail && echo PIPEFAIL_SET; echo pipeline | cat | cat"),
                cEnv,
            )
            row(
                "C child exec through PATH (toybox)",
                listOf(bash.absolutePath, "-c", "ls /system/bin | wc -l; echo CHILD_EXEC_OK"),
                cEnv,
            )
            row(
                "C rm shim through bash (kernel shebang -> /system/bin/sh)",
                listOf(bash.absolutePath, "-c",
                    "mkdir -p c-work; echo doomed > c-work/f.txt; rm c-work/f.txt; " +
                        "echo after-rm: \$(ls c-work); echo trash-has-doomed: \$(ls -a .trash | grep -c f.txt)"),
                mapOf(
                    "PATH" to (bin.shimDir.absolutePath + ":" + "/system/bin"),
                    "PH_TRASH_DIR" to trash.absolutePath,
                ),
            )
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
                env = mapOf("PATH" to chosen.pathPrefix(), "PH_TRASH_DIR" to trash.absolutePath),
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
