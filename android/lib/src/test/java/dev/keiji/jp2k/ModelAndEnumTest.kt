package dev.keiji.jp2k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAndEnumTest {

    @Test
    fun testColorFormat() {
        assertEquals(565, ColorFormat.RGB565.id)
        assertEquals(8888, ColorFormat.ARGB8888.id)

        assertEquals(ColorFormat.RGB565, ColorFormat.valueOf("RGB565"))
        assertEquals(ColorFormat.ARGB8888, ColorFormat.valueOf("ARGB8888"))

        val entries = ColorFormat.entries
        assertEquals(2, entries.size)
        assertTrue(entries.contains(ColorFormat.RGB565))
        assertTrue(entries.contains(ColorFormat.ARGB8888))
    }

    @Test
    fun testState() {
        assertEquals(State.Uninitialized, State.valueOf("Uninitialized"))
        assertEquals(State.Initializing, State.valueOf("Initializing"))
        assertEquals(State.Initialized, State.valueOf("Initialized"))
        assertEquals(State.Processing, State.valueOf("Processing"))
        assertEquals(State.Releasing, State.valueOf("Releasing"))
        assertEquals(State.Released, State.valueOf("Released"))

        val entries = State.entries
        assertEquals(6, entries.size)
    }

    @Test
    fun testConfig() {
        val defaultConfig = Config()
        assertEquals(DEFAULT_MAX_PIXELS, defaultConfig.maxPixels)
        assertEquals(DEFAULT_MAX_HEAP_SIZE_BYTES, defaultConfig.maxHeapSizeBytes)
        assertEquals(DEFAULT_MAX_EVALUATION_RETURN_SIZE_BYTES, defaultConfig.maxEvaluationReturnSizeBytes)
        assertNull(defaultConfig.logLevel)
        assertTrue(defaultConfig.preferDirectBinaryTransfer)
        assertEquals(JavaScriptEngineEnvironment.DEFAULT_BINDER_TRANSACTION_MAX_CHUNK_SIZE_BYTES, defaultConfig.binderTransactionMaxChunkSizeBytes)
        assertEquals(JavaScriptEngineEnvironment.DEFAULT_WASM_MAX_MEMORY_BYTES, defaultConfig.wasmMaxMemoryBytes)

        val customConfig = Config(
            maxPixels = 1000,
            maxHeapSizeBytes = 1024L,
            maxEvaluationReturnSizeBytes = 2048,
            logLevel = 3,
            preferDirectBinaryTransfer = false,
            binderTransactionMaxChunkSizeBytes = 512,
            wasmMaxMemoryBytes = 1024L * 1024L
        )
        assertEquals(1000, customConfig.maxPixels)
        assertEquals(1024L, customConfig.maxHeapSizeBytes)
        assertEquals(2048, customConfig.maxEvaluationReturnSizeBytes)
        assertEquals(Integer.valueOf(3), customConfig.logLevel)
        assertFalse(customConfig.preferDirectBinaryTransfer)
        assertEquals(512, customConfig.binderTransactionMaxChunkSizeBytes)
        assertEquals(1024L * 1024L, customConfig.wasmMaxMemoryBytes)

        val copyConfig = defaultConfig.copy(maxPixels = 500)
        assertEquals(500, copyConfig.maxPixels)
        assertEquals(defaultConfig.maxHeapSizeBytes, copyConfig.maxHeapSizeBytes)

        assertEquals(defaultConfig, defaultConfig.copy())
        assertEquals(defaultConfig.hashCode(), defaultConfig.copy().hashCode())
        assertNotNull(defaultConfig.toString())
    }

    @Test
    fun testMemoryUsage() {
        val memoryUsage = MemoryUsage(1024L)
        assertEquals(1024L, memoryUsage.wasmHeapSizeBytes)

        val copyUsage = memoryUsage.copy(wasmHeapSizeBytes = 2048L)
        assertEquals(2048L, copyUsage.wasmHeapSizeBytes)

        assertEquals(memoryUsage, memoryUsage.copy())
        assertEquals(memoryUsage.hashCode(), memoryUsage.copy().hashCode())
        assertNotNull(memoryUsage.toString())
    }

    @Test
    fun testPerformanceMetrics() {
        val metrics = PerformanceMetrics(
            inputDataSizeBytes = 100L,
            dataTransferTimeMs = 1.0,
            jsDecodeTimeMs = 2.0,
            wasmProcessingTimeMs = 3.0,
            jsEncodeTimeMs = 4.0,
            outputDataSizeBytes = 500L,
            wasmHeapSizeBytes = 1024L,
            totalProcessingTimeMs = 10.0
        )

        assertEquals(100L, metrics.inputDataSizeBytes)
        assertEquals(1.0, metrics.dataTransferTimeMs, 0.001)
        assertEquals(2.0, metrics.jsDecodeTimeMs, 0.001)
        assertEquals(3.0, metrics.wasmProcessingTimeMs, 0.001)
        assertEquals(4.0, metrics.jsEncodeTimeMs, 0.001)
        assertEquals(500L, metrics.outputDataSizeBytes)
        assertEquals(1024L, metrics.wasmHeapSizeBytes)
        assertEquals(10.0, metrics.totalProcessingTimeMs, 0.001)

        val copyMetrics = metrics.copy(wasmProcessingTimeMs = 5.0)
        assertEquals(5.0, copyMetrics.wasmProcessingTimeMs, 0.001)

        assertEquals(metrics, metrics.copy())
        assertEquals(metrics.hashCode(), metrics.copy().hashCode())
        assertNotNull(metrics.toString())
    }

    @Test
    fun testSize() {
        val size = Size(640, 480)
        assertEquals(640, size.width)
        assertEquals(480, size.height)

        val (w, h) = size
        assertEquals(640, w)
        assertEquals(480, h)

        val copySize = size.copy(width = 800)
        assertEquals(800, copySize.width)
        assertEquals(480, copySize.height)

        assertEquals(size, size.copy())
        assertEquals(size.hashCode(), size.copy().hashCode())
        assertNotNull(size.toString())
    }

    @Test
    fun testJp2kErrorAllEntries() {
        val allEntries = Jp2kError.entries
        for (entry in allEntries) {
            val resolved = Jp2kError.fromInt(entry.code)
            assertEquals(entry, resolved)
        }
        assertEquals(Jp2kError.Unknown, Jp2kError.fromInt(99999))
    }

    @Test
    fun testCallbackImplementation() {
        var successResult: String? = null
        var errorException: Exception? = null

        val callback = object : Callback<String> {
            override fun onSuccess(result: String) {
                successResult = result
            }

            override fun onError(error: Exception) {
                errorException = error
            }
        }

        callback.onSuccess("ok")
        assertEquals("ok", successResult)

        val ex = RuntimeException("fail")
        callback.onError(ex)
        assertEquals(ex, errorException)
    }
}
