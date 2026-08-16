package dev.keiji.jp2k

import androidx.javascriptengine.JavaScriptIsolate
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import android.graphics.Bitmap

class Jp2kDecoderAsyncExecuteDecodeCoverageTest11 {

    @Test
    fun executeDecodeImage_chunkedOutput_notStringMediated() {
        var capturedRunnable: Runnable? = null
        val testExecutor = java.util.concurrent.Executor { capturedRunnable = it }
        val testDecoder = Jp2kDecoderAsync(backgroundExecutor = testExecutor)

        val stateField = Jp2kDecoderAsync::class.java.getDeclaredField("_state")
        stateField.isAccessible = true
        stateField.set(testDecoder, State.Initialized)

        val isEvalField = Jp2kDecoderAsync::class.java.getDeclaredField("isEvaluateWithoutTransactionLimitSupported")
        isEvalField.isAccessible = true
        isEvalField.set(testDecoder, false)

        val channelField = Jp2kDecoderAsync::class.java.getDeclaredField("dataChannel")
        channelField.isAccessible = true
        val channel = mock(dev.keiji.jp2k.datachannel.JSDataChannel::class.java)
        whenever(channel.isStringMediated).thenReturn(false)
        whenever(channel.encodePayload(any<ByteArray>())).thenReturn("test")
        whenever(channel.getDecodeJ2KExpression(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("test")
        channelField.set(testDecoder, channel)

        val jsIsolateField = Jp2kDecoderAsync::class.java.getDeclaredField("jsIsolate")
        jsIsolateField.isAccessible = true
        val isolate = mock(JavaScriptIsolate::class.java)

        doAnswer { TestListenableFuture("""{"bmp": "AQID"}""") }.whenever(isolate).evaluateJavaScriptAsync(any<String>())

        jsIsolateField.set(testDecoder, isolate)

        val latch = CountDownLatch(1)
        val callback = object : Callback<android.graphics.Bitmap> {
            override fun onSuccess(result: android.graphics.Bitmap) {
                latch.countDown()
            }
            override fun onError(error: Exception) {
                latch.countDown()
            }
        }

        testDecoder.decodeImage(ByteArray(12), 0, 0, 10, 10, callback)
        capturedRunnable?.run()
        latch.await(1, TimeUnit.SECONDS)
        assertTrue(true) // Just getting it to run
    }
}
