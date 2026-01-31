package dev.keiji.jp2k

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@ExperimentalCoroutinesApi
class Jp2kDecoderAsyncTest {

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

        // Mock sandbox feature support
        whenever(sandbox.isFeatureSupported(any<String>())).thenReturn(true)

        mockBitmapFactory = mockStatic(BitmapFactory::class.java)
        mockBitmapFactory.`when`<Bitmap> {
            BitmapFactory.decodeByteArray(any(), any(), any(), any())
        }.thenReturn(Mockito.mock(Bitmap::class.java))
    }

    @After
    fun tearDown() {
        mockJp2kSandbox.close()
        mockBitmapFactory.close()
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
    fun testPrecache_Success() {
        // Mock evaluateJavaScriptAsync for WASM load (returns "1") and setData (returns "1")
        doAnswer { invocation ->
            // Use simple string matching logic to return appropriate result
            val script = invocation.arguments[0] as String
            TestListenableFuture(INTERNAL_RESULT_SUCCESS)
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val data = ByteArray(10)

        val callbackInit = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.init(context, callbackInit)
        verify(callbackInit).onSuccess(any())

        val callbackPrecache = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.precache(data, callbackPrecache)

        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(contains("setData"))
        verify(callbackPrecache).onSuccess(any())
    }

    @Test
    fun testGetSize_Success() {
        val jsonSize = """{"width": 100, "height": 200}"""

        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("base64ToBytes")) {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            } else if (script.contains("setData")) {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            } else if (script.contains("getSizeWithCache")) {
                TestListenableFuture(jsonSize)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val data = ByteArray(10)

        val callbackInit = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.init(context, callbackInit)
        verify(callbackInit).onSuccess(any())

        val callbackPrecache = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.precache(data, callbackPrecache)
        verify(callbackPrecache).onSuccess(any())

        val callbackSize = org.mockito.kotlin.mock<Callback<Size>>()
        decoder.getSize(callbackSize)

        verify(callbackSize).onSuccess(org.mockito.kotlin.check {
            assertEquals(100, it.width)
            assertEquals(200, it.height)
        })
    }

    @Test
    fun testGetSize_NoDataCached() {
        val jsonError = """{"errorCode": ${Jp2kError.CacheDataMissing.code}, "errorMessage": "No data cached"}"""

        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("base64ToBytes")) {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            } else if (script.contains("getSizeWithCache")) {
                TestListenableFuture(jsonError)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        // Init WITHOUT data
        val callbackInit = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.init(context, callbackInit)
        verify(callbackInit).onSuccess(any())

        val callbackSize = org.mockito.kotlin.mock<Callback<Size>>()
        decoder.getSize(callbackSize)

        verify(callbackSize).onError(any())
    }

    @Test
    fun testDecodeImage_Ratio_Success() {
        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 0, "timeWasm": 0, "timePostProcess": 0}"""

        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("decodeJ2KWithCacheRatio(")) {
                TestListenableFuture(jsonBmp)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val data = ByteArray(10)

        decoder.init(context, org.mockito.kotlin.mock<Callback<Unit>>())
        decoder.precache(data, org.mockito.kotlin.mock<Callback<Unit>>())

        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        decoder.decodeImage(0.0f, 0.0f, 0.5f, 0.5f, callback)

        verify(isolate).evaluateJavaScriptAsync(contains("decodeJ2KWithCacheRatio("))
        verify(callback).onSuccess(any())
    }

    @Test
    fun testDecodeImage_Ratio_InvalidInput() {
        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()

        decoder.decodeImage(0.0f, 0.0f, 1.1f, 0.5f, callback)

        verify(callback).onError(any())
        verify(callback).onError(org.mockito.kotlin.check {
            assertEquals("Ratio must be 0.0 - 1.0", it.message)
        })
    }

    @Test
    fun testInit_Error() {
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

        verify(callback).onError(any())
    }

    @Test
    fun testDecodeImage_DecodeError() {
        val jsonError = """{"errorCode": -4, "errorMessage": "Decode failed"}"""

        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("decodeJ2KWithCacheRatio(")) {
                TestListenableFuture(jsonError)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val data = ByteArray(10)

        decoder.init(context, org.mockito.kotlin.mock<Callback<Unit>>())
        decoder.precache(data, org.mockito.kotlin.mock<Callback<Unit>>())

        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        decoder.decodeImage(0.0f, 0.0f, 0.5f, 0.5f, callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assert(it is Jp2kException)
            assertEquals("Decode failed", it.message)
        })
    }

    @Test
    fun testDecodeImage_RegionOutOfBounds() {
        val jsonError = """{"errorCode": -6, "errorMessage": "Out of bounds"}"""

        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("decodeJ2KWithCacheRatio(")) {
                TestListenableFuture(jsonError)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val data = ByteArray(10)

        decoder.init(context, org.mockito.kotlin.mock<Callback<Unit>>())
        decoder.precache(data, org.mockito.kotlin.mock<Callback<Unit>>())

        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        decoder.decodeImage(0.0f, 0.0f, 0.5f, 0.5f, callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assert(it is RegionOutOfBoundsException)
        })
    }

    @Test
    fun testGetMemoryUsage_Success() {
        val jsonUsage = """{"wasmHeapSizeBytes": 1024}"""

        val decoder = createInitializedDecoder { script ->
            if (script.contains("getMemoryUsage()")) {
                TestListenableFuture(jsonUsage)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        val callback = org.mockito.kotlin.mock<Callback<MemoryUsage>>()
        decoder.getMemoryUsage(callback)

        verify(callback).onSuccess(org.mockito.kotlin.check {
            assertEquals(1024L, it.wasmHeapSizeBytes)
        })
    }

    @Test
    fun testRelease_StateAndCancellation() {
        val decoder = createInitializedDecoder()
        assertEquals(State.Initialized, decoder.state)

        decoder.release()
        assertEquals(State.Released, decoder.state)

        // Verify init returns cancellation
        val callbackInit = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.init(context, callbackInit)
        verify(callbackInit).onError(any())

        // Verify decode returns cancellation
        val callbackDecode = org.mockito.kotlin.mock<Callback<Bitmap>>()
        // Use a valid size array to bypass size check and hit the state check
        decoder.decodeImage(ByteArray(20), callbackDecode)
        verify(callbackDecode).onError(any())

        // Verify precache returns cancellation
        val callbackPrecache = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.precache(ByteArray(10), callbackPrecache)
        verify(callbackPrecache).onError(any())

        // Verify getSize returns cancellation
        val callbackSize = org.mockito.kotlin.mock<Callback<Size>>()
        decoder.getSize(callbackSize)
        verify(callbackSize).onError(any())
    }

    @Test
    fun testInit_AlreadyInitialized() {
        val decoder = createInitializedDecoder()
        assertEquals(State.Initialized, decoder.state)

        Mockito.clearInvocations(isolate)

        val callback = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.init(context, callback)

        verify(callback).onSuccess(any())
        // Should not have called JS again
        verify(isolate, Mockito.never()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testDecodeImage_Uninitialized() {
        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)

        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        decoder.decodeImage(ByteArray(20), callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assert(it is IllegalStateException)
            assertEquals("Cannot decodeImage while in state: Uninitialized", it.message)
        })
    }

    @Test
    fun testGetSize_Uninitialized() {
        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)

        val callback = org.mockito.kotlin.mock<Callback<Size>>()
        decoder.getSize(callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assert(it is IllegalStateException)
            assertEquals("Cannot getSize while in state: Uninitialized", it.message)
        })
    }

    @Test
    fun testPrecache_Uninitialized() {
        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)

        val callback = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.precache(ByteArray(10), callback)

        verify(callback).onError(org.mockito.kotlin.check {
            assert(it is IllegalStateException)
            assertEquals("Cannot precache while in state: Uninitialized", it.message)
        })
    }

    @Test
    fun testDecodeImage_ByteArray_Success() {
        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 0, "timeWasm": 0, "timePostProcess": 0}"""

        val decoder = createInitializedDecoder { script ->
            if (script.contains("decodeJ2K(")) { // Direct decode check
                TestListenableFuture(jsonBmp)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        val data = ByteArray(20)
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        decoder.decodeImage(data, callback)

        verify(isolate).evaluateJavaScriptAsync(contains("decodeJ2K("))
        verify(callback).onSuccess(any())
    }

    @Test
    fun testDecodeImage_Rect_Success() {
        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 0, "timeWasm": 0, "timePostProcess": 0}"""

        val decoder = createInitializedDecoder { script ->
            if (script.contains("decodeJ2KWithCache(")) {
                TestListenableFuture(jsonBmp)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }
        val data = ByteArray(20)

        // Precache manually
        val callbackPrecache = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.precache(data, callbackPrecache)
        verify(callbackPrecache).onSuccess(any())

        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val rect = android.graphics.Rect(0, 0, 10, 10)
        decoder.decodeImage(rect, ColorFormat.ARGB8888, callback)

        // Verify script contains coordinates
        verify(isolate).evaluateJavaScriptAsync(contains("decodeJ2KWithCache("))
        verify(callback).onSuccess(any())
    }

    @Test
    fun testDecodeImage_ByteArray_Ratio() {
        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 0, "timeWasm": 0, "timePostProcess": 0}"""

        val decoder = createInitializedDecoder { script ->
            if (script.contains("decodeJ2KRatio(")) {
                TestListenableFuture(jsonBmp)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        val data = ByteArray(20)
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        decoder.decodeImage(data, 0.0f, 0.0f, 0.5f, 0.5f, ColorFormat.ARGB8888, callback)

        verify(isolate).evaluateJavaScriptAsync(contains("decodeJ2KRatio("))
        verify(callback).onSuccess(any())
    }

    @Test
    fun testDecodeImage_ByteArray_Rect() {
        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 0, "timeWasm": 0, "timePostProcess": 0}"""

        val decoder = createInitializedDecoder { script ->
            if (script.contains("decodeJ2K(")) {
                TestListenableFuture(jsonBmp)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        val data = ByteArray(20)
        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val rect = android.graphics.Rect(0, 0, 100, 100)
        decoder.decodeImage(data, rect, ColorFormat.ARGB8888, callback)

        verify(isolate).evaluateJavaScriptAsync(contains("decodeJ2K("))
        verify(callback).onSuccess(any())
    }
}
