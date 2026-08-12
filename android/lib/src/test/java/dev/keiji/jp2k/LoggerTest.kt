package dev.keiji.jp2k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerTest {

    private class TestLogger : Logger {
        val logs = mutableListOf<Triple<Int, String, String>>()

        override fun println(priority: Int, tag: String, message: String) {
            logs.add(Triple(priority, tag, message))
        }
    }

    @Test
    fun testCustomLoggerReceivesLogs() {
        val testLogger = TestLogger()
        val config = Config(
            logLevel = android.util.Log.INFO,
            logger = testLogger,
        )

        assertEquals(testLogger, config.logger)
    }

    @Test
    fun testChunked64() {
        val longString = "A".repeat(150)
        val chunked = longString.chunked64()
        val lines = chunked.split("\n")

        assertEquals(3, lines.size)
        assertEquals(64, lines[0].length)
        assertEquals(64, lines[1].length)
        assertEquals(22, lines[2].length)
    }

    @Test
    fun testTrimLines() {
        val multiline = (1..20).joinToString("\n") { "Line $it" }
        val trimmed = multiline.trimLines(10)
        val lines = trimmed.split("\n")

        // 5 head lines + 1 truncation indicator line + 5 tail lines = 11 lines
        assertEquals(11, lines.size)
        assertEquals("Line 1", lines[0])
        assertEquals("Line 5", lines[headCountIndex(10)])
        assertTrue(lines[5].contains("truncated 10 lines"))
        assertEquals("Line 16", lines[6])
        assertEquals("Line 20", lines[10])
    }

    private fun headCountIndex(maxLines: Int): Int = (maxLines / 2) - 1
}
