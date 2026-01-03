package dev.keiji.jp2k

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Jp2kDecoderTest {

    private val context = InstrumentationRegistry.getInstrumentation().context
    private lateinit var decoder: Jp2kDecoder

    @Before
    fun setUp() {
        decoder = Jp2kDecoder()
    }

    @After
    fun tearDown() {
        decoder.release()
    }

    @Test
    fun testInit() = runTest {
        decoder.init(context)
    }

    @Test
    fun testDecode() = runTest {
        val bytes = context.assets.open("karin.jp2").use {
            it.readBytes()
        }

        decoder.init(context)
        val bitmap = decoder.decodeImage(bytes)

        assertNotNull(bitmap)
        // Based on hex dump analysis: Height=480, Width=640
        assertEquals(640, bitmap.width)
        assertEquals(480, bitmap.height)
    }

    @Test
    fun testDecodeBeforeInit() = runTest {
        val bytes = ByteArray(100)
        try {
            decoder.decodeImage(bytes)
            fail("Should throw exception")
        } catch (e: Exception) {
            // Expected
             assertTrue("Expected IllegalStateException, got $e", e is IllegalStateException)
        }
    }

    @Test
    fun testDecodeAfterRelease() = runTest {
        decoder.init(context)
        decoder.release()

        val bytes = ByteArray(100)
        try {
            decoder.decodeImage(bytes)
            fail("Should throw exception")
        } catch (e: Exception) {
             // Expected
             assertTrue("Expected CancellationException, got $e", e is java.util.concurrent.CancellationException)
        }
    }

    @Test
    fun testConcurrentDecode() = runTest {
        decoder.init(context)
        val bytes = context.assets.open("karin.jp2").use { it.readBytes() }

        // Launch multiple decodes
        val deferreds = (1..3).map {
            async {
                decoder.decodeImage(bytes)
            }
        }

        val bitmaps = deferreds.awaitAll()
        assertEquals(3, bitmaps.size)
        bitmaps.forEach {
            assertNotNull(it)
            assertEquals(640, it.width)
        }
    }

    @Test
    fun testGetMemoryUsage() = runTest {
        decoder.init(context)
        val usage = decoder.getMemoryUsage()
        assertNotNull(usage)
        assert(usage.wasmHeapSizeBytes > 0)
    }
}
