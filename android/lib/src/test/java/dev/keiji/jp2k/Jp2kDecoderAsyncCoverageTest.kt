package dev.keiji.jp2k

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@ExperimentalCoroutinesApi
class Jp2kDecoderAsyncCoverageTest {

    @Mock
    lateinit var context: Context

    @Mock
    lateinit var assetManager: AssetManager

    @Mock
    lateinit var sandbox: JavaScriptSandbox

    @Mock
    lateinit var isolate: JavaScriptIsolate

    private lateinit var mockJp2kSandbox: MockedStatic<Jp2kSandbox>
    private lateinit var mockBitmapFactory: MockedStatic<BitmapFactory>
    private lateinit var mockLog: MockedStatic<android.util.Log>

    class TestListenableFuture<T>(private val result: T) : ListenableFuture<T> {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true
        override fun get(): T = result
        override fun get(timeout: Long, unit: TimeUnit?): T = result
        override fun addListener(listener: Runnable, executor: Executor) {
            listener.run()
        }
    }

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        whenever(context.assets).thenReturn(assetManager)
        whenever(assetManager.open(any<String>())).thenReturn(ByteArrayInputStream(ByteArray(0)))

        mockJp2kSandbox = mockStatic(Jp2kSandbox::class.java)

        mockJp2kSandbox.`when`<ListenableFuture<JavaScriptSandbox>> {
            Jp2kSandbox.get(any<Context>())
        }.thenReturn(TestListenableFuture(sandbox))

        mockJp2kSandbox.`when`<JavaScriptIsolate> {
            Jp2kSandbox.createIsolate(any(), any(), any())
        }.thenReturn(isolate)

        whenever(sandbox.isFeatureSupported(any<String>())).thenReturn(true)

        mockBitmapFactory = mockStatic(BitmapFactory::class.java)
        mockBitmapFactory.`when`<Bitmap> {
            BitmapFactory.decodeByteArray(any(), any(), any(), any())
        }.thenReturn(Mockito.mock(Bitmap::class.java))

        mockLog = mockStatic(android.util.Log::class.java)
    }

    @After
    fun tearDown() {
        mockJp2kSandbox.close()
        mockBitmapFactory.close()
        mockLog.close()
    }

    private fun createInitializedDecoder(
        scriptHandler: (String) -> TestListenableFuture<String> = { TestListenableFuture(INTERNAL_RESULT_SUCCESS) }
    ): Jp2kDecoderAsync {
        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            scriptHandler(script)
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        decoder.init(context, org.mockito.kotlin.mock<Callback<Unit>>())
        return decoder
    }

    @Test
    fun testDecodeImage_ColorFormat_Only() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()

        // This exercises the overload: decodeImage(ColorFormat, Callback)
        decoder.decodeImage(ColorFormat.ARGB8888, callback)

        // It should eventually call executeDecodeImage
        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testDecodeImage_IntCoords_NoColorFormat() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(12)
        decoder.precache(data, org.mockito.kotlin.mock<Callback<Unit>>())

        // Exercises: decodeImage(x, y, w, h, Callback)
        decoder.decodeImage(0, 0, 100, 100, callback)

        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testDecodeImage_Rect_NoColorFormat() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(12)
        decoder.precache(data, org.mockito.kotlin.mock<Callback<Unit>>())
        val rect = Rect(0, 0, 100, 100)

        // Exercises: decodeImage(Rect, Callback)
        decoder.decodeImage(rect, callback)

        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testDecodeImage_FloatCoords_NoColorFormat() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(12)
        decoder.precache(data, org.mockito.kotlin.mock<Callback<Unit>>())

        // Exercises: decodeImage(left, top, right, bottom, Callback)
        decoder.decodeImage(0f, 0f, 1f, 1f, callback)

        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testDecodeImage_ByteArray_Only() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(12)

        // Exercises: decodeImage(ByteArray, Callback)
        decoder.decodeImage(data, callback)

        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testDecodeImage_ByteArray_IntCoords_NoColorFormat() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(12)

        // Exercises: decodeImage(ByteArray, x, y, w, h, Callback)
        decoder.decodeImage(data, 0, 0, 100, 100, callback)

        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testDecodeImage_ByteArray_Rect_NoColorFormat() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(12)
        val rect = Rect(0, 0, 100, 100)

        // Exercises: decodeImage(ByteArray, Rect, Callback)
        decoder.decodeImage(data, rect, callback)

        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testDecodeImage_ByteArray_FloatCoords_NoColorFormat() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(12)

        // Exercises: decodeImage(ByteArray, left, top, right, bottom, Callback)
        decoder.decodeImage(data, 0f, 0f, 1f, 1f, callback)

        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testClose() {
         val decoder = createInitializedDecoder()
         // Just verify it doesn't crash and potentially check interactions if close did anything significant
         // The close() method in Jp2kDecoderAsync is: fun close() { release() }
         decoder.close()

         // Verify release logic was triggered (state changes to Released)
         // We can't access state directly if it's private, but we know release changes it.
         // Wait, state is public or at least internal.
         // Let's rely on Jp2kDecoderAsyncTest which showed state check.

         // Assuming close calls release
         verify(isolate, Mockito.atLeastOnce()).close()
    }

    @Test
    fun testDecodeImage_NoArgs() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        decoder.precache(ByteArray(10), org.mockito.kotlin.mock<Callback<Unit>>())

        // Exercises: decodeImage(Callback)
        decoder.decodeImage(callback)

        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testDecodeImage_LoggingEnabled() {
        val config = Config(logLevel = android.util.Log.VERBOSE)

        // Custom creation to inject config
        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor, config = config)

        doAnswer { invocation ->
            TestListenableFuture(INTERNAL_RESULT_SUCCESS)
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        decoder.init(context, org.mockito.kotlin.mock<Callback<Unit>>())
        decoder.precache(ByteArray(10), org.mockito.kotlin.mock<Callback<Unit>>())

        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 10, "timeWasm": 20, "timePostProcess": 30}"""

        doAnswer {
            TestListenableFuture(jsonBmp)
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        decoder.decodeImage(callback)

        verify(callback).onSuccess(any())
    }

    @Test
    fun testDecodeImage_CacheMissing() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        decoder.precache(ByteArray(10), org.mockito.kotlin.mock<Callback<Unit>>())

        val jsonError = """{"errorCode": -10, "errorMessage": "No data cached"}"""
        doAnswer {
            TestListenableFuture(jsonError)
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        decoder.decodeImage(callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is IllegalStateException)
            assertEquals("No data cached", it.message)
        })
    }

    @Test
    fun testDecodeImage_GenericError() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        decoder.precache(ByteArray(10), org.mockito.kotlin.mock<Callback<Unit>>())

        val jsonError = """{"error": "Some random error"}"""
        doAnswer {
            TestListenableFuture(jsonError)
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        decoder.decodeImage(callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is Jp2kException)
            assertEquals("Some random error", it.message)
        })
    }

    @Test
    fun testPrecache_Error() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Unit>>()
        val data = ByteArray(10)

        val jsonError = """{"errorCode": -5, "errorMessage": "Setup failed"}"""
        doAnswer {
            TestListenableFuture(jsonError)
        }.whenever(isolate).evaluateJavaScriptAsync(org.mockito.ArgumentMatchers.contains("setData"))

        decoder.precache(data, callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is Jp2kException)
            assertEquals("Setup failed", it.message)
        })
    }

    @Test
    fun testPrecache_Exception() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Unit>>()
        val data = ByteArray(10)

        doAnswer {
            throw RuntimeException("JS Error")
        }.whenever(isolate).evaluateJavaScriptAsync(org.mockito.ArgumentMatchers.contains("setData"))

        decoder.precache(data, callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is RuntimeException)
            assertEquals("JS Error", it.message)
        })
    }

    @Test
    fun testGetSize_Error() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Size>>()

        val jsonError = """{"errorCode": -4, "errorMessage": "Get size failed"}"""
        doAnswer {
            TestListenableFuture(jsonError)
        }.whenever(isolate).evaluateJavaScriptAsync(org.mockito.ArgumentMatchers.contains("getSizeWithCache"))

        decoder.getSize(callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is Jp2kException)
            assertEquals("Get size failed", it.message)
        })
    }

    @Test
    fun testGetSize_Exception() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Size>>()

        doAnswer {
            throw RuntimeException("JS Error")
        }.whenever(isolate).evaluateJavaScriptAsync(org.mockito.ArgumentMatchers.contains("getSizeWithCache"))

        decoder.getSize(callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is RuntimeException)
            assertEquals("JS Error", it.message)
        })
    }
}
