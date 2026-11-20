package com.zcode.quickocr

/**
 * 进程内存内的最近识别记录（最多保留 5 条，不落盘，遵循零留存隐私边界）。
 */
object OcrHistory {

    private const val MAX_ENTRIES = 5

    data class Entry(
        val id: Long,
        val lines: List<String>,
        val timestampMs: Long
    ) {
        fun preview(maxChars: Int = 42): String {
            val joined = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            return if (joined.length <= maxChars) joined else joined.take(maxChars) + "…"
        }
    }

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var nextId = 1L

    fun add(lines: List<String>) {
        if (lines.isEmpty()) return
        val copy = lines.toList()
        synchronized(lock) {
            if (entries.firstOrNull()?.lines == copy) return
            entries.addFirst(Entry(nextId++, copy, System.currentTimeMillis()))
            while (entries.size > MAX_ENTRIES) {
                entries.removeLast()
            }
        }
    }

    fun list(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }
}
