package com.arsvie.pocketharness.platform

import ph.policy.TrustStore
import java.io.File

/**
 * Per-folder trust, persisted as a small JSON string array in `filesDir` (ADR-004 §2).
 *
 * Paths are canonicalised before they are compared or stored, so `/x/y/../y` and `/x/y` are the same
 * folder. The file is rewritten atomically (tmp + rename) on every change; a corrupt or missing file
 * reads as "nothing trusted" rather than throwing at startup.
 *
 * Only ever consulted in [ph.policy.ExecutionMode.DEFAULT]; YOLO skips the gate entirely.
 */
class FileTrustStore(private val file: File) : TrustStore {

    private val lock = Any()
    private val trusted = LinkedHashSet<String>()

    init {
        load()
    }

    override fun isTrusted(cwd: String): Boolean = synchronized(lock) {
        trusted.contains(canonical(cwd))
    }

    override fun trust(cwd: String) {
        synchronized(lock) {
            if (trusted.add(canonical(cwd))) persist()
        }
    }

    override fun revoke(cwd: String) {
        synchronized(lock) {
            if (trusted.remove(canonical(cwd))) persist()
        }
    }

    private fun canonical(cwd: String): String = try {
        File(cwd).canonicalPath
    } catch (_: Exception) {
        File(cwd).absolutePath
    }

    private fun load() {
        if (!file.isFile) return
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return
        }
        trusted.clear()
        trusted.addAll(parseArray(text))
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val body = trusted.joinToString(separator = ",", prefix = "[", postfix = "]") { quote(it) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            file.writeText(body)
            tmp.delete()
        }
    }

    // ---- the smallest JSON this app needs; no serializer dependency in :app --------------------

    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun parseArray(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '"') {
                i++
                continue
            }
            i++
            val sb = StringBuilder()
            while (i < text.length && text[i] != '"') {
                val ch = text[i]
                if (ch == '\\' && i + 1 < text.length) {
                    i++
                    when (val esc = text[i]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = text.substring(i + 1, minOf(i + 5, text.length))
                            hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            i += 4
                        }

                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(ch)
                }
                i++
            }
            i++
            out.add(sb.toString())
        }
        return out
    }
}
