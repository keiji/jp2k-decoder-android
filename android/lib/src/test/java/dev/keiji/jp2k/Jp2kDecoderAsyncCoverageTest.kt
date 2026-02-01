package dev.keiji.jp2k

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
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
    fun testDecodeImage_RectF_NoColorFormat() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(12)
        decoder.precache(data, org.mockito.kotlin.mock<Callback<Unit>>())
        val rectF = RectF(0f, 0f, 0.5f, 0.5f)

        // Exercises: decodeImage(RectF, Callback)
        decoder.decodeImage(rectF, callback)

        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testDecodeImage_ByteArray_RectF_NoColorFormat() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(12)
        val rectF = RectF(0f, 0f, 0.5f, 0.5f)

        // Exercises: decodeImage(ByteArray, RectF, Callback)
        decoder.decodeImage(data, rectF, callback)

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

    @Test
    fun testInit_AlreadyInitialized() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Unit>>()

        // Call init again
        decoder.init(context, callback)

        // Should success immediately
        verify(callback).onSuccess(Unit)
    }

    @Test
    fun testInit_Released() {
        val decoder = createInitializedDecoder()
        decoder.release()

        val callback = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.init(context, callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is java.util.concurrent.CancellationException)
            assertEquals("Decoder was released.", it.message)
        })
    }

    @Test
    fun testInit_StateError() {
        val decoder = createInitializedDecoder()
        // Simulate processing state
        val stateField = Jp2kDecoderAsync::class.java.getDeclaredField("_state")
        stateField.isAccessible = true
        stateField.set(decoder, State.Processing)

        val callback = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.init(context, callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is IllegalStateException)
            assertTrue(it.message!!.contains("Cannot initialize while in state"))
        })
    }

    @Test
    fun testInit_SandboxFailure() {
        val exception = RuntimeException("Sandbox creation failed")
        val failedFuture = object : ListenableFuture<JavaScriptSandbox> {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
            override fun isCancelled(): Boolean = false
            override fun isDone(): Boolean = true
            override fun get(): JavaScriptSandbox { throw java.util.concurrent.ExecutionException(exception) }
            override fun get(timeout: Long, unit: TimeUnit?): JavaScriptSandbox { throw java.util.concurrent.ExecutionException(exception) }
            override fun addListener(listener: Runnable, executor: Executor) {
                listener.run()
            }
        }

        mockJp2kSandbox.`when`<ListenableFuture<JavaScriptSandbox>> {
            Jp2kSandbox.get(any<Context>())
        }.thenReturn(failedFuture)

        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val callback = org.mockito.kotlin.mock<Callback<Unit>>()

        decoder.init(context, callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is java.util.concurrent.ExecutionException)
            assertEquals("Sandbox creation failed", it.cause?.message ?: it.message)
        })
    }

    @Test
    fun testInit_IsolateFailure() {
        val exception = RuntimeException("Isolate creation failed")

        mockJp2kSandbox.`when`<JavaScriptIsolate> {
            Jp2kSandbox.createIsolate(any(), any(), any())
        }.thenThrow(exception)

        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val callback = org.mockito.kotlin.mock<Callback<Unit>>()

        decoder.init(context, callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is RuntimeException)
        })
    }

    @Test
    fun testLoadWasm_Failure() {
         // Setup isolate to fail on loadWasm
        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)

        doAnswer { invocation ->
            TestListenableFuture("0")
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val callback = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.init(context, callback)

         verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is IllegalStateException)
            assertEquals("WASM instantiation failed.", it.message)
        })
    }

    @Test
    fun testLoadWasm_Exception() {
         // Setup isolate to fail on loadWasm
        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)

        val exception = RuntimeException("WASM Error")
        doAnswer { invocation ->
            val future = org.mockito.kotlin.mock<ListenableFuture<String>>()
            whenever(future.get()).thenThrow(java.util.concurrent.ExecutionException(exception))
            future
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val callback = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.init(context, callback)

         verify(callback).onError(org.mockito.kotlin.check {
            assertEquals("WASM Error", it.message)
        })
    }

    @Test
    fun testGetMemoryUsage_Success() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<MemoryUsage>>()

        val jsonUsage = """{"wasmHeapSizeBytes": 2048}"""
        doAnswer {
            TestListenableFuture(jsonUsage)
        }.whenever(isolate).evaluateJavaScriptAsync(org.mockito.ArgumentMatchers.contains("getMemoryUsage"))

        decoder.getMemoryUsage(callback)

        verify(callback).onSuccess(org.mockito.kotlin.check {
            assertEquals(2048L, it.wasmHeapSizeBytes)
        })
    }

    @Test
    fun testGetMemoryUsage_Error() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<MemoryUsage>>()

        val exception = RuntimeException("JS Error")
        doAnswer {
            throw exception
        }.whenever(isolate).evaluateJavaScriptAsync(org.mockito.ArgumentMatchers.contains("getMemoryUsage"))

        decoder.getMemoryUsage(callback)

        verify(callback).onError(exception)
    }

    @Test
    fun testGetMemoryUsage_StateError() {
        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val callback = org.mockito.kotlin.mock<Callback<MemoryUsage>>()

        // Uninitialized
        decoder.getMemoryUsage(callback)
        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is IllegalStateException)
            assertTrue(it.message!!.contains("Cannot getMemoryUsage while in state"))
        })
    }

    @Test
    fun testDecodeImage_InvalidRatio() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(20)

        decoder.decodeImage(data, -0.1f, 0f, 0.5f, 0.5f, callback)
        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is IllegalArgumentException)
            assertEquals("Ratio must be 0.0 - 1.0", it.message)
        })
    }

    @Test
    fun testDecodeImage_InvalidRatio_Precaching() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()

        decoder.decodeImage(-0.1f, 0f, 0.5f, 0.5f, callback)
        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is IllegalArgumentException)
            assertEquals("Ratio must be 0.0 - 1.0", it.message)
        })
    }

    @Test
    fun testDecodeImage_InputTooShort() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(5) // Too short

        decoder.decodeImage(data, callback)
        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is IllegalArgumentException)
            assertEquals("Input data is too short", it.message)
        })
    }

    @Test
    fun testDecodeImage_BitmapNull() {
        val decoder = createInitializedDecoder()
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(20)

        val jsonBmp = """{"bmp": "AQID"}"""
        doAnswer {
            TestListenableFuture(jsonBmp)
        }.whenever(isolate).evaluateJavaScriptAsync(org.mockito.ArgumentMatchers.contains("decodeJ2K"))

        // Mock BitmapFactory to return null
        mockBitmapFactory.`when`<Bitmap> {
            BitmapFactory.decodeByteArray(any(), any(), any(), any())
        }.thenReturn(null)

        decoder.decodeImage(data, callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is IllegalStateException)
            assertEquals("Bitmap decoding failed (returned null).", it.message)
        })
    }

    @Test
    fun testPrecache_StateError() {
        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val callback = org.mockito.kotlin.mock<Callback<Unit>>()

        decoder.precache(ByteArray(20), callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is IllegalStateException)
            assertTrue(it.message!!.contains("Cannot precache while in state"))
        })
    }

    @Test
    fun testGetSize_StateError() {
        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val callback = org.mockito.kotlin.mock<Callback<Size>>()

        decoder.getSize(callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is IllegalStateException)
            assertTrue(it.message!!.contains("Cannot getSize while in state"))
        })
    }

    @Test
    fun testDecodeImage_StateError() {
        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()

        decoder.decodeImage(ByteArray(20), callback)

        verify(callback).onError(org.mockito.kotlin.check {
             assertTrue(it is IllegalStateException)
             assertTrue(it.message!!.contains("Cannot decodeImage while in state"))
        })
    }

    @Test
    fun testRelease_Exception() {
         val decoder = createInitializedDecoder()

         doAnswer { throw RuntimeException("Close failed") }.whenever(isolate).close()

         decoder.release()

         // Should verify no crash
    }

    @Test
    fun testDecodeImage_ConcurrentRelease() {
        val capturedRunnable = ArgumentCaptor.forClass(Runnable::class.java)
        val mockExecutor = Mockito.mock(Executor::class.java)
        val decoder = Jp2kDecoderAsync(backgroundExecutor = mockExecutor)

        // Manual init
        val stateField = Jp2kDecoderAsync::class.java.getDeclaredField("_state")
        stateField.isAccessible = true
        stateField.set(decoder, State.Initialized)
        val jsIsolateField = Jp2kDecoderAsync::class.java.getDeclaredField("jsIsolate")
        jsIsolateField.isAccessible = true
        jsIsolateField.set(decoder, isolate)

        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        decoder.decodeImage(ByteArray(20), callback)

        verify(mockExecutor).execute(capturedRunnable.capture())
        val runnable = capturedRunnable.value

        // Now call release
        decoder.release()

        // Now run the runnable
        runnable.run()

        // Verify callback error "Decoder was released."
        verify(callback).onError(org.mockito.kotlin.check {
            assertTrue(it is java.util.concurrent.CancellationException)
            assertEquals("Decoder was released.", it.message)
        })
    }
}
