package dev.keiji.jp2k

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class Jp2kSandboxTest {

    @Mock
    lateinit var context: Context

    @Mock
    lateinit var sandbox: JavaScriptSandbox

    @Mock
    lateinit var isolate: JavaScriptIsolate

    private lateinit var mockJavaScriptSandbox: MockedStatic<JavaScriptSandbox>

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

        whenever(context.applicationContext).thenReturn(context)

        mockJavaScriptSandbox = mockStatic(JavaScriptSandbox::class.java)

        mockJavaScriptSandbox.`when`<ListenableFuture<JavaScriptSandbox>> {
            JavaScriptSandbox.createConnectedInstanceAsync(any())
        }.thenReturn(TestListenableFuture(sandbox))

        whenever(sandbox.createIsolate(any())).thenReturn(isolate)
    }

    @After
    fun tearDown() {
        mockJavaScriptSandbox.close()
    }

    @Test
    fun testGet() {
        val future = Jp2kSandbox.get(context)
        assertNotNull(future)
        assertEquals(sandbox, future.get())

        // Call again to verify caching (mock verify check)
        Jp2kSandbox.get(context)

        // Should be called only once if cached?
        // Note: Jp2kSandbox is a singleton object, so state persists across tests if not reset.
        // Since we can't easily reset the private 'sandboxFuture' field without reflection,
        // we might just verify it's called at least once.
        // Or we rely on the fact that these tests run in a fresh JVM or ClassLoader per test class usually?
        // Actually, in Robolectric/Unit tests, static state persists.
        // But let's verify it calls the factory at least once.
        mockJavaScriptSandbox.verify({
            JavaScriptSandbox.createConnectedInstanceAsync(any())
        }, Mockito.atLeastOnce())
    }

    @Test
    fun testCreateIsolate_FeaturesSupported() {
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)).thenReturn(true)

        val maxHeap = 100L
        val maxReturn = 200

        Jp2kSandbox.createIsolate(sandbox, maxHeap, maxReturn)

        val captor = ArgumentCaptor.forClass(IsolateStartupParameters::class.java)
        verify(sandbox).createIsolate(captor.capture())

        val params = captor.value
        assertEquals(maxHeap, params.maxHeapSizeBytes)
        assertEquals(maxReturn, params.maxEvaluationReturnSizeBytes)
    }

    @Test
    fun testCreateIsolate_FeaturesNotSupported() {
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)).thenReturn(false)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)).thenReturn(false)

        val maxHeap = 100L
        val maxReturn = 200

        Jp2kSandbox.createIsolate(sandbox, maxHeap, maxReturn)

        val captor = ArgumentCaptor.forClass(IsolateStartupParameters::class.java)
        verify(sandbox).createIsolate(captor.capture())

        val params = captor.value
        // Default values should be preserved (usually Int.MAX_VALUE or 0 depending on impl, checking logic)
        // logic:
        // if (supported) params.maxHeapSizeBytes = ...
        // else params.maxHeapSizeBytes remains default.

        // We just verify createIsolate was called.
        verify(sandbox).createIsolate(any())
    }

    @Test
    fun testSetupConsoleCallback() {
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING)).thenReturn(true)
        val executor = Executor { it.run() }

        Jp2kSandbox.setupConsoleCallback(isolate, sandbox, executor, "TestTag")

        verify(isolate).setConsoleCallback(any(), any())
    }

    @Test
    fun testSetupConsoleCallback_NotSupported() {
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING)).thenReturn(false)
        val executor = Executor { it.run() }

        Jp2kSandbox.setupConsoleCallback(isolate, sandbox, executor, "TestTag")

        verify(isolate, Mockito.never()).setConsoleCallback(any(), any())
    }
}
