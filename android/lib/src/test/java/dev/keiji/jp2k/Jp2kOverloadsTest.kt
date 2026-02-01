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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
class Jp2kOverloadsTest {

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

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)

        whenever(context.assets).thenReturn(assetManager)
        whenever(assetManager.open(any<String>())).thenReturn(ByteArrayInputStream(ByteArray(0)))

        mockJp2kSandbox = mockStatic(Jp2kSandbox::class.java)
        mockJp2kSandbox.`when`<ListenableFuture<JavaScriptSandbox>> { Jp2kSandbox.get(any<Context>()) }
            .thenReturn(TestListenableFuture(sandbox))
        mockJp2kSandbox.`when`<JavaScriptIsolate> { Jp2kSandbox.createIsolate(any(), any(), any()) }
            .thenReturn(isolate)
        whenever(sandbox.isFeatureSupported(any<String>())).thenReturn(true)

        mockBitmapFactory = mockStatic(BitmapFactory::class.java)
        mockBitmapFactory.`when`<Bitmap> { BitmapFactory.decodeByteArray(any(), any(), any(), any()) }
            .thenReturn(Mockito.mock(Bitmap::class.java))

        mockLog = mockStatic(android.util.Log::class.java)
    }

    @After
    fun tearDown() {
        mockJp2kSandbox.close()
        mockBitmapFactory.close()
        mockLog.close()
        Dispatchers.resetMain()
    }

    @Test
    fun testJp2kDecoderAsyncOverloads() {
        val directExecutor = Executor { it.run() }
        val config = Config(logLevel = android.util.Log.VERBOSE)
        val decoder = Jp2kDecoderAsync(backgroundExecutor = directExecutor, config = config)

        doAnswer { TestListenableFuture(INTERNAL_RESULT_SUCCESS) }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        decoder.init(context, org.mockito.kotlin.mock())
        decoder.precache(ByteArray(12), org.mockito.kotlin.mock())

        val callback = org.mockito.kotlin.mock<Callback<Bitmap>>()
        val data = ByteArray(12)
        val rect = Rect(0, 0, 10, 10)
        val rectF = RectF(0f, 0f, 0.5f, 0.5f)

        // Prepare mock for decode execution
        val jsonBmp = """{"bmp": "AQID"}"""
        doAnswer { TestListenableFuture(jsonBmp) }.whenever(isolate).evaluateJavaScriptAsync(org.mockito.ArgumentMatchers.contains("decodeJ2K"))

        // Cached overloads
        decoder.decodeImage(ColorFormat.ARGB8888, callback)
        decoder.decodeImage(0, 0, 10, 10, ColorFormat.ARGB8888, callback)
        decoder.decodeImage(0, 0, 10, 10, callback)
        decoder.decodeImage(rect, ColorFormat.ARGB8888, callback)
        decoder.decodeImage(rect, callback)
        decoder.decodeImage(0f, 0f, 0.5f, 0.5f, ColorFormat.ARGB8888, callback)
        decoder.decodeImage(0f, 0f, 0.5f, 0.5f, callback)
        decoder.decodeImage(rectF, ColorFormat.ARGB8888, callback)
        decoder.decodeImage(rectF, callback)

        // Data overloads
        decoder.decodeImage(data, ColorFormat.ARGB8888, callback)
        decoder.decodeImage(data, 0, 0, 10, 10, ColorFormat.ARGB8888, callback)
        decoder.decodeImage(data, 0, 0, 10, 10, callback)
        decoder.decodeImage(data, rect, ColorFormat.ARGB8888, callback)
        decoder.decodeImage(data, rect, callback)
        decoder.decodeImage(data, rectF, ColorFormat.ARGB8888, callback)
        decoder.decodeImage(data, rectF, callback)
        decoder.decodeImage(data, 0f, 0f, 0.5f, 0.5f, ColorFormat.ARGB8888, callback)
        decoder.decodeImage(data, 0f, 0f, 0.5f, 0.5f, callback)

        // Just verify called many times
        verify(isolate, Mockito.atLeast(18)).evaluateJavaScriptAsync(any<String>())
    }

    @Test
    fun testJp2kDecoderOverloads() = runTest {
        val config = Config(logLevel = android.util.Log.VERBOSE)
        // Use testDispatcher
        val decoder = Jp2kDecoder(config = config, coroutineDispatcher = testDispatcher)

        doAnswer { TestListenableFuture(INTERNAL_RESULT_SUCCESS) }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        decoder.init(context)
        decoder.precache(ByteArray(12))

        val data = ByteArray(12)
        val rect = Rect(0, 0, 10, 10)
        val rectF = RectF(0f, 0f, 0.5f, 0.5f)
        val jsonBmp = """{"bmp": "AQID"}"""
        doAnswer { TestListenableFuture(jsonBmp) }.whenever(isolate).evaluateJavaScriptAsync(org.mockito.ArgumentMatchers.contains("decodeJ2K"))

        // Cached overloads
        decoder.decodeImage(ColorFormat.ARGB8888)
        decoder.decodeImage(0, 0, 10, 10, ColorFormat.ARGB8888)
        decoder.decodeImage(rect, ColorFormat.ARGB8888)
        decoder.decodeImage(rectF, ColorFormat.ARGB8888)
        decoder.decodeImage(0f, 0f, 0.5f, 0.5f, ColorFormat.ARGB8888)

        // Data overloads
        decoder.decodeImage(data, ColorFormat.ARGB8888)
        decoder.decodeImage(data, 0, 0, 10, 10, ColorFormat.ARGB8888)
        decoder.decodeImage(data, rect, ColorFormat.ARGB8888)
        decoder.decodeImage(data, rectF, ColorFormat.ARGB8888)
        decoder.decodeImage(data, 0f, 0f, 0.5f, 0.5f, ColorFormat.ARGB8888)

        verify(isolate, Mockito.atLeast(10)).evaluateJavaScriptAsync(any<String>())
    }
}
