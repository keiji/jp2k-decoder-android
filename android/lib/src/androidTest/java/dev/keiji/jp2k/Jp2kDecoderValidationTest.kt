package dev.keiji.jp2k

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Jp2kDecoderValidationTest {

    private val context = InstrumentationRegistry.getInstrumentation().context
    private lateinit var decoder: Jp2kDecoder

    @Before
    fun setUp() {
        decoder = Jp2kDecoder(context)
    }

    @After
    fun tearDown() {
        decoder.release()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDecodeTooSmall() = runTest {
        val bytes = ByteArray(10) // Less than MIN_INPUT_SIZE (12)
        decoder.decodeImage(bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDecodeZeroLength() = runTest {
        val bytes = ByteArray(0)
        decoder.decodeImage(bytes)
    }
}
