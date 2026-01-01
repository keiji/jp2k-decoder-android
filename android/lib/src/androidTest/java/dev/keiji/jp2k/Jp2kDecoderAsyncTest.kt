package dev.keiji.jp2k

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class Jp2kDecoderAsyncTest {

    private val context = InstrumentationRegistry.getInstrumentation().context
    private lateinit var decoder: Jp2kDecoderAsync

    @Before
    fun setUp() {
        decoder = Jp2kDecoderAsync(context)
    }

    @After
    fun tearDown() {
        decoder.release()
    }

    @Test
    fun testInitAsync() {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Unit?>()
        val errorRef = AtomicReference<Throwable?>()

        decoder.init(object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                resultRef.set(Unit)
                latch.countDown()
            }

            override fun onError(error: Exception) {
                errorRef.set(error)
                latch.countDown()
            }
        })

        val completed = latch.await(5, TimeUnit.SECONDS)
        if (!completed) {
            fail("Test timed out")
        }

        val error = errorRef.get()
        if (error != null) {
            fail("Decoding failed with error: ${error.message}")
        }

        val result = resultRef.get()
        assertNotNull("result should not be null", result)
    }

    @Test
    fun testDecodeAsync() {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Bitmap?>()
        val errorRef = AtomicReference<Throwable?>()

        val bytes = context.assets.open("karin.jp2").use {
            it.readBytes()
        }

        fun decodeImage() {
            decoder.decodeImage(bytes, object : Callback<Bitmap> {
                override fun onSuccess(result: Bitmap) {
                    resultRef.set(result)
                    latch.countDown()
                }

                override fun onError(error: Exception) {
                    errorRef.set(error)
                    latch.countDown()
                }
            })
        }
        decoder.init(object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                decodeImage()
            }

            override fun onError(error: Exception) {
                errorRef.set(error)
                latch.countDown()
            }
        })

        val completed = latch.await(5, TimeUnit.SECONDS)
        if (!completed) {
            fail("Test timed out")
        }

        val error = errorRef.get()
        if (error != null) {
            fail("Decoding failed with error: ${error.message}")
        }

        val bitmap = resultRef.get()
        assertNotNull("Bitmap should not be null", bitmap)
        assertEquals(640, bitmap!!.width)
        assertEquals(480, bitmap.height)
    }
}
