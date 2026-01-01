package dev.keiji.jp2k

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Jp2kDecoderTest {

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

    @Test
    fun testDecode() = runTest {
        val inputStream = context.assets.open("karin.jp2")
        val bytes = inputStream.readBytes()
        inputStream.close()

        val bitmap = decoder.decodeImage(bytes)

        assertNotNull(bitmap)
        // Based on hex dump analysis: Height=480, Width=640
        assertEquals(640, bitmap.width)
        assertEquals(480, bitmap.height)
    }
}
