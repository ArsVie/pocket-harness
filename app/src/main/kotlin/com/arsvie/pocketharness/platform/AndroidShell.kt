package com.arsvie.pocketharness.platform

import android.os.Process as AndroidProcess
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ph.ports.ExecResult
import ph.ports.Shell
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * The real shell on the device, in-process (ADR-001).
 *
 * Which binary that is was decided empirically, not by argument: [AndroidShellBinaries.resolve]
 * self-tests a command-carrying invocation and ExecProbe records the raw evidence in
 * `files/exec-probe.txt`. One shape now (ADR-006): `<shell> -c <command>`, where `<shell>` is the
 * bundled GNU bash (`files/userland/bash`) or, failing its self-test, `/system/bin/sh`.
 *
 * Decisions, documented as the task requires:
 *  - **Invocation**: `ProcessBuilder(binaries.argv(command))`. No system shell is consulted unless
 *    the self-test proved the platform shell is the only one that works.
 *  - **PATH**: the `rm` shim dir FIRST, then `/system/bin` ([ShellBinaries.pathPrefix]),
 *    so a bare `rm` hits the shim (ADR-004 §5).
 *  - **env**: the caller's map is exported verbatim over the inherited environment (the tool layer
 *    passes `PH_TRASH_DIR`); PATH is set last so a caller cannot accidentally shadow the shims.
 *  - **timeout**: supplied by the caller from `Budgets`; enforced by destroying the process **tree**
 *    and reporting `timedOut = true`. The command's budget is the caller's; the only numbers in this
 *    file are the named constant-block properties at the bottom (see each for why it is not preset data).
 *
 * B-19 lives here as much as in the loop: a command may background a reader (`head` parked on a
 * socket) that outlives the shell that started it. Killing only the direct child leaves that orphan
 * holding the stdout pipe, and the reader `join()` then never returns — the exec call never reports
 * its timeout and the turn hangs for ever. [killTree] and the bounded drains below are the fix.
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

        // argv is `<shell> -c <command>`; [AndroidShellBinaries.argv] owns the choice of shell.
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
        // other would otherwise deadlock at the 64 KiB pipe buffer. They drain in chunks rather than
        // `readText()` so that a pipe abandoned below still yields everything the command wrote.
        val outReader = Thread { drain(process.inputStream, stdout) }
        val errReader = Thread { drain(process.errorStream, stderr) }
        outReader.start(); errReader.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        var timedOut = false
        if (!finished) {
            timedOut = true
            killTree(process)
        }
        // B-19: bounded, never unbounded. Once the tree is dead the pipes reach EOF at once; a reader
        // still parked here is holding a pipe whose writer escaped the kill, and the turn gets its
        // answer — whatever the pipe had delivered — instead of hanging on it.
        outReader.join(PIPE_ABANDON_GRACE_MS)
        errReader.join(PIPE_ABANDON_GRACE_MS)

        ExecResult(
            // Under the same locks the readers append under: a still-parked reader cannot corrupt the
            // snapshot, and the snapshot cannot miss a chunk that has already landed.
            stdout = synchronized(stdout) { stdout.toString() },
            stderr = synchronized(stderr) { stderr.toString() },
            exitCode = process.exitValue(),
            timedOut = timedOut,
        )
    }

    /** Reads one pipe to EOF in chunks, appending each chunk to [sink] under the sink's own lock. */
    private fun drain(stream: InputStream, sink: StringBuilder) {
        runCatching {
            stream.bufferedReader().use { reader ->
                val chunk = CharArray(PIPE_CHUNK_CHARS)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    synchronized(sink) { sink.appendRange(chunk, 0, read) }
                }
            }
        }
    }

    /**
     * Kills the command's whole process tree, not only the direct child.
     *
     * A shell that backgrounds a reader (`head` parked on a socket) leaves that orphan holding the
     * stdout pipe after the wrapper dies: the drains in [exec] get no EOF, and the turn spends the
     * grace waiting for a pipe nobody will close.
     *
     * The tree is found through `/proc`, not through `ProcessHandle`: Android ships neither
     * `java.lang.ProcessHandle` nor `Process.pid()`/`Process.toHandle()` (android.jar has only
     * `waitFor`/`exitValue`/`destroy`/`destroyForcibly`), so the parent links in `/proc` are the only
     * handle on a child's pid. The walk starts at *this* process, because that is the one pid we can
     * name (`android.os.Process.myPid()`): every child it has is the shell, and everything below that
     * is the command's own tree. Deepest first, so nothing gets to spawn a replacement mid-kill.
     */
    private fun killTree(process: Process) {
        val byParent = processesByParent()
        for (pid in descendantsOf(AndroidProcess.myPid(), byParent)) {
            runCatching { AndroidProcess.sendSignal(pid, OsConstants.SIGKILL) }
        }
        // The direct child sits at the root of that walk and has just been SIGKILLed; this reaps it.
        process.destroyForcibly()
        process.waitFor()
    }

    /** `/proc` read as `ppid -> pids`. Entries that cannot be read (another uid, already gone) are skipped. */
    private fun processesByParent(): Map<Int, List<Int>> {
        val byParent = mutableMapOf<Int, MutableList<Int>>()
        for (entry in File(PROC_DIR).listFiles().orEmpty()) {
            val pid = entry.name.toIntOrNull() ?: continue
            val stat = runCatching { File(entry, PROC_STAT_FILE).readText() }.getOrNull() ?: continue
            val parent = parentPidOf(stat) ?: continue
            byParent.getOrPut(parent) { mutableListOf() } += pid
        }
        return byParent
    }

    /**
     * The parent pid out of one `/proc/<pid>/stat` line. `comm` is the only field that may contain
     * spaces and parentheses, so everything after the *last* `)` is `state ppid pgrp …` — the field
     * after `state` is the parent.
     */
    private fun parentPidOf(stat: String): Int? {
        val fields = stat.substringAfterLast(')').trim().split(' ').filter { it.isNotEmpty() }
        return fields.getOrNull(1)?.toIntOrNull()
    }

    /** Every pid below [root], deepest first. */
    private fun descendantsOf(root: Int, byParent: Map<Int, List<Int>>): List<Int> {
        val ordered = mutableListOf<Int>()
        for (child in byParent[root].orEmpty()) {
            ordered += descendantsOf(child, byParent)
            ordered += child
        }
        return ordered
    }

    private companion object {
        /** Subdirectory of the working directory handed to the child as TMPDIR (a name, not a path). */
        const val TMP_DIR_NAME = ".tmp"

        const val PROC_DIR = "/proc"
        const val PROC_STAT_FILE = "stat"

        /** Pipe-drain chunk. A buffer size, not a policy: it bounds how much a parked reader holds back. */
        const val PIPE_CHUNK_CHARS = 8 * 1024

        /**
         * How long a stdout/stderr drain may block before its pipe is abandoned (B-19).
         *
         * This is a property of the kill and the OS pipes — how long EOF can still be in flight after
         * a SIGKILL — not of the agent loop, which is why it is not a field of `Budgets`: the command's
         * own budget is `Budgets.commandTimeoutMs` and the caller still passes it in as [exec]'s
         * `timeoutMs`. In the ordinary case the tree kill closes the pipes immediately and none of
         * this is spent at all.
         */
        const val PIPE_ABANDON_GRACE_MS = 2_000L
    }
}
