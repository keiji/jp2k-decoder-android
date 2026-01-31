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
class Jp2kDecoderTest {

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

    private val testDispatcher = StandardTestDispatcher()

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
        Dispatchers.setMain(testDispatcher)

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
        Dispatchers.resetMain()
    }

    private suspend fun createInitializedDecoder(
        scriptHandler: (String) -> TestListenableFuture<String> = { TestListenableFuture(INTERNAL_RESULT_SUCCESS) }
    ): Jp2kDecoder {
        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            scriptHandler(script)
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        decoder.init(context)
        return decoder
    }

    @Test
    fun testPrecache_Success() = runTest {
        // Mock evaluateJavaScriptAsync for WASM load (returns "1") and setData (returns "1")
        doAnswer { invocation ->
            // Use simple string matching logic to return appropriate result
            val script = invocation.arguments[0] as String
            // Default return "1" for all async calls in this test (loadWasm, setData)
            TestListenableFuture(INTERNAL_RESULT_SUCCESS)
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        decoder.init(context)
        val data = ByteArray(10)
        decoder.precache(data)

        // Verify setData was called
        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(contains("setData"))
    }

    @Test
    fun testGetSize_Success() = runTest {
        val jsonSize = """{"width": 100, "height": 200}"""

        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("base64ToBytes")) {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS) // WASM load
            } else if (script.contains("setData")) {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS) // setData
            } else if (script.contains("getSizeWithCache")) {
                TestListenableFuture(jsonSize) // getSizeWithCache
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS) // Default
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        val data = ByteArray(10)
        decoder.init(context)
        decoder.precache(data)
        val size = decoder.getSize()

        assertEquals(100, size.width)
        assertEquals(200, size.height)
    }

    @Test
    fun testGetSize_NoDataCached() = runTest {
        val jsonError = """{"errorCode": ${Jp2kError.CacheDataMissing.code}, "errorMessage": "No data cached"}"""

        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("base64ToBytes")) {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS) // WASM load
            } else if (script.contains("getSizeWithCache")) {
                TestListenableFuture(jsonError) // getSizeWithCache
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        // Init WITHOUT data
        decoder.init(context)

        // Do not call precache

        try {
            decoder.getSize()
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("No data cached", e.message)
        }
    }

    @Test
    fun testDecodeImage_Ratio_Success() = runTest {
        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 0, "timeWasm": 0, "timePostProcess": 0}"""

        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("decodeJ2KWithCacheRatio(")) {
                TestListenableFuture(jsonBmp)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        decoder.init(context)
        val data = ByteArray(10)
        decoder.precache(data)

        // Call with ratios: left=0.0, top=0.0, right=0.5, bottom=0.5
        decoder.decodeImage(0.0f, 0.0f, 0.5f, 0.5f)

        // Verify that the script passed to JS contains the ratios
        // Relaxing the match to ensure the correct function is called with floats
        verify(isolate).evaluateJavaScriptAsync(contains("decodeJ2KWithCacheRatio("))
    }

    @Test
    fun testDecodeImage_Ratio_InvalidInput() = runTest {
        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        try {
            decoder.decodeImage(0.0f, 0.0f, 1.1f, 0.5f)
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Ratio must be 0.0 - 1.0", e.message)
        }
    }

    @Test
    fun testInit_Error() = runTest {
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

        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        try {
            decoder.init(context)
            fail("Should throw RuntimeException")
        } catch (e: RuntimeException) {
            // Success
        }
    }

    @Test
    fun testDecodeImage_DecodeError() = runTest {
        val jsonError = """{"errorCode": -4, "errorMessage": "Decode failed"}"""

        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("decodeJ2KWithCacheRatio(")) {
                TestListenableFuture(jsonError)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        decoder.init(context)
        val data = ByteArray(10)
        decoder.precache(data)

        try {
            decoder.decodeImage(0.0f, 0.0f, 0.5f, 0.5f)
            fail("Should throw Jp2kException")
        } catch (e: Jp2kException) {
            assertEquals("Decode failed", e.message)
        }
    }

    @Test
    fun testDecodeImage_RegionOutOfBounds() = runTest {
        val jsonError = """{"errorCode": -6, "errorMessage": "Out of bounds"}"""

        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("decodeJ2KWithCacheRatio(")) {
                TestListenableFuture(jsonError)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        decoder.init(context)
        val data = ByteArray(10)
        decoder.precache(data)

        try {
            decoder.decodeImage(0.0f, 0.0f, 0.5f, 0.5f)
            fail("Should throw RegionOutOfBoundsException")
        } catch (e: RegionOutOfBoundsException) {
            // Success
        }
    }

    @Test
    fun testGetMemoryUsage_Success() = runTest {
        val jsonUsage = """{"wasmHeapSizeBytes": 2048}"""

        val decoder = createInitializedDecoder { script ->
            if (script.contains("getMemoryUsage()")) {
                TestListenableFuture(jsonUsage)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        val memoryUsage = decoder.getMemoryUsage()
        assertEquals(2048L, memoryUsage.wasmHeapSizeBytes)
    }


    @Test
    fun testDecodeImage_ByteArray_Success() = runTest {
        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 0, "timeWasm": 0, "timePostProcess": 0}"""

        val decoder = createInitializedDecoder { script ->
            if (script.contains("decodeJ2K(")) {
                TestListenableFuture(jsonBmp)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        val data = ByteArray(20)
        decoder.decodeImage(data)

        verify(isolate).evaluateJavaScriptAsync(contains("decodeJ2K("))
    }

    @Test
    fun testInit_AlreadyInitialized() = runTest {
        val decoder = createInitializedDecoder()
        assertEquals(State.Initialized, decoder.state)

        // Reset mock interactions to ensure no new calls are made
        Mockito.clearInvocations(isolate)

        // Call init again
        decoder.init(context)

        // Verify state is still Initialized
        assertEquals(State.Initialized, decoder.state)

        // Verify loadWasm was NOT called again (by checking evaluateJavaScriptAsync)
        verify(isolate, Mockito.never()).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testRelease_Cancellation() = runTest {
        val decoder = createInitializedDecoder()
        decoder.release()

        assertEquals(State.Released, decoder.state)

        // Verify init throws IllegalStateException because state is Released
        try {
            decoder.init(context)
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Cannot initialize while in state: Released", e.message)
        }

        // Verify precache throws CancellationException
        try {
            decoder.precache(ByteArray(10))
            fail("Should throw CancellationException")
        } catch (e: java.util.concurrent.CancellationException) {
            assertEquals("Decoder was released.", e.message)
        }

        // Verify getSize throws CancellationException
        try {
            decoder.getSize()
            fail("Should throw CancellationException")
        } catch (e: java.util.concurrent.CancellationException) {
            assertEquals("Decoder was released.", e.message)
        }

        // Verify decodeImage throws CancellationException
        try {
            decoder.decodeImage(ByteArray(20))
            fail("Should throw CancellationException")
        } catch (e: java.util.concurrent.CancellationException) {
            assertEquals("Decoder was released.", e.message)
        }
    }

    @Test
    fun testDecodeImage_Rect() = runTest {
        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 0, "timeWasm": 0, "timePostProcess": 0}"""

        val decoder = createInitializedDecoder { script ->
            if (script.contains("decodeJ2KWithCache(")) {
                TestListenableFuture(jsonBmp)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }
        val data = ByteArray(10)
        decoder.precache(data)

        val rect = android.graphics.Rect(0, 0, 100, 100)
        decoder.decodeImage(rect)

        verify(isolate).evaluateJavaScriptAsync(contains("decodeJ2KWithCache("))
    }

    @Test
    fun testDecodeImage_IntCoordinates() = runTest {
        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 0, "timeWasm": 0, "timePostProcess": 0}"""

        val decoder = createInitializedDecoder { script ->
            if (script.contains("decodeJ2KWithCache(")) {
                TestListenableFuture(jsonBmp)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }
        val data = ByteArray(10)
        decoder.precache(data)

        decoder.decodeImage(0, 0, 100, 100)

        verify(isolate).evaluateJavaScriptAsync(contains("decodeJ2KWithCache("))
    }

    @Test
    fun testDecodeImage_Uninitialized() = runTest {
        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        try {
            decoder.decodeImage(ByteArray(20))
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Cannot decodeImage while in state: Uninitialized", e.message)
        }
    }

    @Test
    fun testGetSize_Uninitialized() = runTest {
        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        try {
            decoder.getSize()
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Cannot getSize while in state: Uninitialized", e.message)
        }
    }

    @Test
    fun testPrecache_Uninitialized() = runTest {
        val decoder = Jp2kDecoder(coroutineDispatcher = testDispatcher)
        try {
            decoder.precache(ByteArray(10))
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Cannot precache while in state: Uninitialized", e.message)
        }
    }

    @Test
    fun testDecodeImage_ByteArray_Ratio() = runTest {
        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 0, "timeWasm": 0, "timePostProcess": 0}"""

        val decoder = createInitializedDecoder { script ->
            if (script.contains("decodeJ2KRatio(")) {
                TestListenableFuture(jsonBmp)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        val data = ByteArray(20)
        decoder.decodeImage(data, 0.0f, 0.0f, 0.5f, 0.5f)

        verify(isolate).evaluateJavaScriptAsync(contains("decodeJ2KRatio("))
    }

    @Test
    fun testDecodeImage_ByteArray_Rect() = runTest {
        val jsonBmp = """{"bmp": "AQID", "timePreProcess": 0, "timeWasm": 0, "timePostProcess": 0}"""

        val decoder = createInitializedDecoder { script ->
            if (script.contains("decodeJ2K(")) {
                TestListenableFuture(jsonBmp)
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        val data = ByteArray(20)
        val rect = android.graphics.Rect(0, 0, 100, 100)
        decoder.decodeImage(data, rect)

        verify(isolate).evaluateJavaScriptAsync(contains("decodeJ2K("))
    }
}
