package dev.keiji.jp2k

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class Jp2kDecoderAsyncValidationTest {

    private val context = InstrumentationRegistry.getInstrumentation().context
    private lateinit var decoder: Jp2kDecoderAsync

    @Before
    fun setUp() {
        decoder = Jp2kDecoderAsync()
        val latch = CountDownLatch(1)
        decoder.init(context, object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                latch.countDown()
            }

            override fun onError(error: Exception) {
                latch.countDown()
            }
        })
        latch.await(5, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        decoder.release()
    }

    @Test
    fun testDecodeTooSmall() {
        val bytes = ByteArray(10) // Less than MIN_INPUT_SIZE (12)
        val latch = CountDownLatch(1)

        decoder.decodeImage(bytes, object : Callback<Bitmap> {
            override fun onSuccess(result: Bitmap) {
                fail("Should have failed")
                latch.countDown()
            }

            override fun onError(error: Exception) {
                if (error is IllegalArgumentException) {
                    // Passed
                } else {
                    fail("Expected IllegalArgumentException but got $error")
                }
                latch.countDown()
            }
        })

        latch.await(5, TimeUnit.SECONDS)
    }
}
