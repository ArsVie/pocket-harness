package com.arsvie.pocketharness.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ph.ports.ExecResult
import ph.ports.Shell
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The real shell on the device, in-process (ADR-001).
 *
 * Which binary that is was decided empirically, not by argument: [AndroidShellBinaries.resolve]
 * self-tests an applet-carrying invocation and ExecProbe records the raw evidence in
 * `files/exec-probe.txt`. Two shapes exist:
 *  - `BusyBox`  — `<busybox> sh -c <command>`
 *  - `Platform` — `/system/bin/sh -c <command>` (bionic mksh, built for the app seccomp filter)
 *
 * Decisions, documented as the task requires:
 *  - **Invocation**: `ProcessBuilder(binaries.argv(command))`. No system shell is consulted unless
 *    the self-test proved the platform shell is the only one that works.
 *  - **PATH**: the `rm` shim dir FIRST, then the shell's applet dir ([ShellBinaries.pathPrefix]),
 *    so a bare `rm` hits the shim (ADR-004 §5).
 *  - **env**: the caller's map is exported verbatim over the inherited environment (the tool layer
 *    passes `PH_TRASH_DIR`); PATH is set last so a caller cannot accidentally shadow the shims.
 *  - **timeout**: supplied by the caller from `Budgets`; enforced by destroying the process and
 *    reporting `timedOut = true`. This class holds no literal tunable.
 */
class AndroidShell(private val binaries: AndroidShellBinaries) : Shell {

    override suspend fun exec(
        command: String,
        cwd: String,
        timeoutMs: Long,
        env: Map<String, String>,
    ): ExecResult = withContext(Dispatchers.IO) {
        // The child's env is derived from the cwd argument, never from a literal path: HOME is the
        // working directory (a live transcript showed `echo HOME=$HOME` printing an empty value and
        // `ls "$HOME"` dying with `ls: : No such file or directory`), and TMPDIR is a `.tmp`
        // subdirectory of it, created here so tools that write temp files have somewhere legal.
        val home = File(cwd).absoluteFile
        val tmp = File(home, TMP_DIR_NAME).apply { mkdirs() }

        // argv is `<shell> sh -c <command>` for busybox, `<shell> -c <command>` for the platform
        // shell; [AndroidShellBinaries.argv] owns that difference.
        val process = ProcessBuilder(binaries.argv(command))
            .directory(home)
            .apply {
                environment()["PATH"] = binaries.pathPrefix()
                environment()["HOME"] = home.absolutePath
                environment()["TMPDIR"] = tmp.absolutePath
                // Caller-supplied vars are exported verbatim and may override the above (the tool
                // layer passes PH_TRASH_DIR); PATH stays as [pathPrefix] unless the caller sets it.
                env.forEach { (k, v) -> environment()[k] = v }
            }
            .start()

        // The command string is the whole input; close stdin so a read gets EOF, not a hang.
        process.outputStream.close()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        // Separate readers on separate threads: a child that fills one pipe while we block on the
        // other would otherwise deadlock at the 64 KiB pipe buffer.
        val outReader = Thread { process.inputStream.bufferedReader().use { stdout.append(it.readText()) } }
        val errReader = Thread { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
        outReader.start(); errReader.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        var timedOut = false
        if (!finished) {
            timedOut = true
            process.destroyForcibly()
            process.waitFor()
        }
        outReader.join(); errReader.join()

        ExecResult(
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            exitCode = process.exitValue(),
            timedOut = timedOut,
        )
    }

    private companion object {
        /** Subdirectory of the working directory handed to the child as TMPDIR (a name, not a path). */
        const val TMP_DIR_NAME = ".tmp"
    }
}
