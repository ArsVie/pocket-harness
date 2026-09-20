package com.arsvie.pocketharness.platform

import java.io.File

/**
 * Manual session-list state (B-15): the display order and the pinned set, persisted as one small
 * JSON object in `filesDir`. Order is stored oldest-first in the file, but renders top-first:
 *
 *   display order = pinned ids (in order), then unpinned ids (in order)
 *
 * The file is rewritten atomically (tmp + rename) on every change; a corrupt or missing file reads
 * as "empty", never throws. [reconcile] keeps it in step with the store: ids the store no longer
 * knows are dropped, ids it has never seen arrive at the top (newest first).
 */
class FileSessionUiStore(private val file: File) {

    private val lock = Any()
    private val order = mutableListOf<String>()
    private val pinned = LinkedHashSet<String>()

    init {
        load()
    }

    /** The manual display order, top of the list first. */
    fun orderedIds(): List<String> = synchronized(lock) { order.toList() }

    fun pinnedIds(): Set<String> = synchronized(lock) { LinkedHashSet(pinned) }

    /**
     * Keeps this store in step with the session store. [knownIds] arrives newest-first: vanished ids
     * are dropped from both order and pins; never-seen ids are prepended (newest ends up at index 0).
     */
    fun reconcile(knownIds: List<String>) {
        synchronized(lock) {
            val known = knownIds.toSet()
            var changed = order.removeAll { it !in known }
            changed = pinned.removeAll { it !in known } || changed
            val fresh = knownIds.filter { it !in order }
            if (fresh.isNotEmpty()) {
                for (id in fresh.asReversed()) order.add(0, id)
                changed = true
            }
            if (changed) persist()
        }
    }

    fun setPinned(id: String, pinnedNow: Boolean) {
        synchronized(lock) {
            val changed = if (pinnedNow) pinned.add(id) else pinned.remove(id)
            if (changed) persist()
        }
    }

    /** Moves a session one step up/down within its own pin-block; block edges and unknown ids are no-ops. */
    fun move(id: String, up: Boolean) {
        synchronized(lock) {
            val from = order.indexOf(id)
            if (from < 0) return
            val inPinnedBlock = id in pinned
            var to = from + if (up) -1 else 1
            while (to in order.indices && (order[to] in pinned) != inPinnedBlock) {
                to += if (up) -1 else 1
            }
            if (to !in order.indices) return
            order.removeAt(from)
            order.add(to, id)
            persist()
        }
    }

    /** Called when a session is deleted: no trace is left in order or pins. */
    fun forget(id: String) {
        synchronized(lock) {
            val inOrder = order.remove(id)
            val inPins = pinned.remove(id)
            if (inOrder || inPins) persist()
        }
    }

    private fun load() {
        if (!file.isFile) return
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return
        }
        order.clear()
        order.addAll(arrayAfter(text, ORDER_KEY).distinct())
        pinned.clear()
        pinned.addAll(arrayAfter(text, PINNED_KEY).filter { it in order })
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val body = buildString {
            append("{\"order\":[")
            append(order.joinToString(separator = ",") { quote(it) })
            append("],\"pinned\":[")
            append(pinned.joinToString(separator = ",") { quote(it) })
            append("]}")
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            file.writeText(body)
            tmp.delete()
        }
    }

    // ---- the smallest JSON this app needs; no serializer dependency in :app --------------------

    private fun arrayAfter(text: String, key: String): List<String> {
        val at = text.indexOf(key)
        if (at < 0) return emptyList()
        val start = text.indexOf('[', at)
        val end = text.indexOf(']', start + 1)
        if (start < 0 || end < 0) return emptyList()
        return parseStrings(text.substring(start, end + 1))
    }

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

    private fun parseStrings(text: String): List<String> {
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

    private companion object {
        const val ORDER_KEY = "\"order\""
        const val PINNED_KEY = "\"pinned\""
    }
}
