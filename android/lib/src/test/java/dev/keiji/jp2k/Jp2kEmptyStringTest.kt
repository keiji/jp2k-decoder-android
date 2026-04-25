package dev.keiji.jp2k

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class Jp2kEmptyStringTest {

    @Mock
    lateinit var context: Context

    @Mock
    lateinit var sandbox: JavaScriptSandbox

    @Mock
    lateinit var isolate: JavaScriptIsolate

    private lateinit var mockJp2kSandbox: MockedStatic<Jp2kSandbox>
    private lateinit var mockBitmapFactory: MockedStatic<BitmapFactory>

    private val EXPECTED_ERROR_MESSAGE = "JS engine returned empty result - expression may have evaluated to a non-string type"

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

        val assetManager = mock(android.content.res.AssetManager::class.java)
        whenever(context.assets).thenReturn(assetManager)
        whenever(assetManager.open(any<String>())).thenReturn(ByteArrayInputStream(ByteArray(0)))

        mockJp2kSandbox = mockStatic(Jp2kSandbox::class.java)
        mockJp2kSandbox.`when`<ListenableFuture<JavaScriptSandbox>> {
            Jp2kSandbox.get(any<Context>())
        }.thenReturn(TestListenableFuture(sandbox))
        mockJp2kSandbox.`when`<JavaScriptIsolate> {
            Jp2kSandbox.createIsolate(any(), any(), any())
        }.thenReturn(isolate)

        mockBitmapFactory = mockStatic(BitmapFactory::class.java)
    }

    @After
    fun tearDown() {
        mockJp2kSandbox.close()
        mockBitmapFactory.close()
    }

    private fun createInitializedDecoderAsync(
        scriptHandler: (String) -> TestListenableFuture<String>
    ): Jp2kDecoderAsync {
        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("base64ToBytes")) {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            } else {
                scriptHandler(script)
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        decoder.init(context, object : Callback<Unit> {
            override fun onSuccess(result: Unit) {}
            override fun onError(error: Exception) {}
        })
        return decoder
    }

    private fun createInitializedDecoder(
        scriptHandler: (String) -> TestListenableFuture<String>
    ): Jp2kDecoder {
        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("base64ToBytes")) {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            } else {
                scriptHandler(script)
            }
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val decoder = Jp2kDecoder()
        runBlocking {
            decoder.init(context)
        }
        return decoder
    }

    @Test
    fun testAsyncPrecache_EmptyString_ThrowsIllegalStateException() {
        val decoder = createInitializedDecoderAsync { script ->
            if (script.contains("setData")) {
                TestListenableFuture("") // Empty string
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        var error: Exception? = null
        decoder.precache(ByteArray(20), object : Callback<Unit> {
            override fun onSuccess(result: Unit) {}
            override fun onError(e: Exception) {
                error = e
            }
        })

        assertTrue("Error should be IllegalStateException, but was $error", error is IllegalStateException)
        assertEquals(EXPECTED_ERROR_MESSAGE, error?.message)
    }

    @Test
    fun testAsyncGetSize_EmptyString_ThrowsIllegalStateException() {
        val decoder = createInitializedDecoderAsync { script ->
            if (script.contains("getSize")) {
                TestListenableFuture("") // Empty string
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        var error: Exception? = null
        decoder.getSize(ByteArray(20), object : Callback<Size> {
            override fun onSuccess(result: Size) {}
            override fun onError(e: Exception) {
                error = e
            }
        })

        assertTrue("Error should be IllegalStateException, but was $error", error is IllegalStateException)
        assertEquals(EXPECTED_ERROR_MESSAGE, error?.message)
    }

    @Test
    fun testAsyncDecodeImage_EmptyString_ThrowsIllegalStateException() {
        val decoder = createInitializedDecoderAsync { script ->
            if (script.contains("decodeJ2K")) {
                TestListenableFuture("") // Empty string
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        var error: Exception? = null
        decoder.decodeImage(ByteArray(20), object : Callback<Bitmap> {
            override fun onSuccess(result: Bitmap) {}
            override fun onError(e: Exception) {
                error = e
            }
        })

        assertTrue("Error should be IllegalStateException, but was $error", error is IllegalStateException)
        assertEquals(EXPECTED_ERROR_MESSAGE, error?.message)
    }

    @Test
    fun testAsyncGetMemoryUsage_EmptyString_ThrowsIllegalStateException() {
        val decoder = createInitializedDecoderAsync { script ->
            if (script.contains("getMemoryUsage()")) {
                TestListenableFuture("") // Empty string
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        var error: Exception? = null
        decoder.getMemoryUsage(object : Callback<MemoryUsage> {
            override fun onSuccess(result: MemoryUsage) {}
            override fun onError(e: Exception) {
                error = e
            }
        })

        assertTrue("Error should be IllegalStateException, but was $error", error is IllegalStateException)
        assertEquals(EXPECTED_ERROR_MESSAGE, error?.message)
    }

    @Test
    fun testPrecache_EmptyString_ThrowsIllegalStateException() = runBlocking {
        val decoder = createInitializedDecoder { script ->
            if (script.contains("setData")) {
                TestListenableFuture("") // Empty string
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        try {
            decoder.precache(ByteArray(20))
            assertTrue("Should throw IllegalStateException", false)
        } catch (e: IllegalStateException) {
            assertEquals(EXPECTED_ERROR_MESSAGE, e.message)
        }
    }

    @Test
    fun testGetSize_EmptyString_ThrowsIllegalStateException() = runBlocking {
        val decoder = createInitializedDecoder { script ->
            if (script.contains("getSize")) {
                TestListenableFuture("") // Empty string
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        try {
            decoder.getSize(ByteArray(20))
            assertTrue("Should throw IllegalStateException", false)
        } catch (e: IllegalStateException) {
            assertEquals(EXPECTED_ERROR_MESSAGE, e.message)
        }
    }

    @Test
    fun testDecodeImage_EmptyString_ThrowsIllegalStateException() = runBlocking {
        val decoder = createInitializedDecoder { script ->
            if (script.contains("decodeJ2K")) {
                TestListenableFuture("") // Empty string
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        try {
            decoder.decodeImage(ByteArray(20))
            assertTrue("Should throw IllegalStateException", false)
        } catch (e: IllegalStateException) {
            assertEquals(EXPECTED_ERROR_MESSAGE, e.message)
        }
    }

    @Test
    fun testGetMemoryUsage_EmptyString_ThrowsIllegalStateException() = runBlocking {
        val decoder = createInitializedDecoder { script ->
            if (script.contains("getMemoryUsage()")) {
                TestListenableFuture("") // Empty string
            } else {
                TestListenableFuture(INTERNAL_RESULT_SUCCESS)
            }
        }

        try {
            decoder.getMemoryUsage()
            assertTrue("Should throw IllegalStateException", false)
        } catch (e: IllegalStateException) {
            assertEquals(EXPECTED_ERROR_MESSAGE, e.message)
        }
    }
}
