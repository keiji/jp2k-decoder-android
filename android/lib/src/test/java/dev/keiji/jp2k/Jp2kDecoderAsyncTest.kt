package dev.keiji.jp2k

import android.content.Context
import android.content.res.AssetManager
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
    }

    @After
    fun tearDown() {
        mockJp2kSandbox.close()
    }

    @Test
    fun testInitWithData_Success() {
        // Mock evaluateJavaScriptAsync for WASM load (returns "1") and setData (returns "1")
        doAnswer { invocation ->
            // Use simple string matching logic to return appropriate result
            val script = invocation.arguments[0] as String
            TestListenableFuture(INTERNAL_RESULT_SUCCESS)
        }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        val directExecutor = Executor { it.run() }
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor)
        val data = ByteArray(10)

        val callback = org.mockito.kotlin.mock<Callback<Unit>>()
        decoder.init(context, data, callback)

        verify(isolate, Mockito.atLeastOnce()).evaluateJavaScriptAsync(contains("setData"))
        verify(callback).onSuccess(any())
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
        decoder.init(context, data, callbackInit)
        verify(callbackInit).onSuccess(any())

        val callbackSize = org.mockito.kotlin.mock<Callback<Size>>()
        decoder.getSize(callbackSize)

        verify(callbackSize).onSuccess(org.mockito.kotlin.check {
            assertEquals(100, it.width)
            assertEquals(200, it.height)
        })
    }

    @Test
    fun testGetSize_NoDataCached() {
        val jsonError = """{"errorCode": -10, "errorMessage": "No data cached"}"""

        doAnswer { invocation ->
            val script = invocation.arguments[0] as String
            if (script.contains("base64ToBytes")) {
                TestListenableFuture("1")
            } else if (script.contains("getSizeWithCache")) {
                TestListenableFuture(jsonError)
            } else {
                TestListenableFuture("1")
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
}
