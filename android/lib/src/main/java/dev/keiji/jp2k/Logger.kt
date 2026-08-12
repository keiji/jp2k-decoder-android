package dev.keiji.jp2k

import android.util.Log

/**
 * Interface for logging abstraction.
 */
interface Logger {
    /**
     * Writes a log message with specified priority and tag.
     */
    fun println(priority: Int, tag: String, message: String)
}

/**
 * Default implementation of [Logger] using [android.util.Log].
 */
object AndroidLogger : Logger {
    override fun println(priority: Int, tag: String, message: String) {
        Log.println(priority, tag, message)
    }
}

internal fun String.chunked64(): String = this.chunked(64).joinToString("\n")

internal fun String.trimLines(maxLines: Int = 10): String {
    if (maxLines <= 0) return this
    val lines = this.lines()
    if (lines.size <= maxLines) return this

    val headCount = maxLines / 2
    val tailCount = maxLines - headCount
    val truncatedCount = lines.size - maxLines

    val head = lines.take(headCount)
    val tail = lines.takeLast(tailCount)

    return (head + "... (truncated $truncatedCount lines) ..." + tail).joinToString("\n")
}
