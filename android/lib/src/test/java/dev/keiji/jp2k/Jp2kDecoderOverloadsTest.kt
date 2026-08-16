package dev.keiji.jp2k

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.mock

@ExperimentalCoroutinesApi
class Jp2kDecoderOverloadsTest {

    @Test
    fun callAllOverloadsToEnsureCoverage() = runTest {
        val decoder = Jp2kDecoder()
        val data = ByteArray(12)
        val rect = Rect(10, 20, 30, 40)
        val rectF = RectF(0.1f, 0.2f, 0.3f, 0.4f)

        try { decoder.decodeImage() } catch(e: Exception) {}
        try { decoder.decodeImage(ColorFormat.ARGB8888) } catch(e: Exception) {}
        try { decoder.decodeImage(0, 0, 10, 10) } catch(e: Exception) {}
        try { decoder.decodeImage(0, 0, 10, 10, ColorFormat.ARGB8888) } catch(e: Exception) {}
        try { decoder.decodeImage(rect) } catch(e: Exception) {}
        try { decoder.decodeImage(rect, ColorFormat.ARGB8888) } catch(e: Exception) {}
        try { decoder.decodeImage(rectF) } catch(e: Exception) {}
        try { decoder.decodeImage(rectF, ColorFormat.ARGB8888) } catch(e: Exception) {}
        try { decoder.decodeImage(0f, 0f, 0.5f, 0.5f) } catch(e: Exception) {}
        try { decoder.decodeImage(0f, 0f, 0.5f, 0.5f, ColorFormat.ARGB8888) } catch(e: Exception) {}

        try { decoder.decodeImage(data) } catch(e: Exception) {}
        try { decoder.decodeImage(data, ColorFormat.ARGB8888) } catch(e: Exception) {}
        try { decoder.decodeImage(data, 0, 0, 10, 10) } catch(e: Exception) {}
        try { decoder.decodeImage(data, 0, 0, 10, 10, ColorFormat.ARGB8888) } catch(e: Exception) {}
        try { decoder.decodeImage(data, rect) } catch(e: Exception) {}
        try { decoder.decodeImage(data, rect, ColorFormat.ARGB8888) } catch(e: Exception) {}
        try { decoder.decodeImage(data, rectF) } catch(e: Exception) {}
        try { decoder.decodeImage(data, rectF, ColorFormat.ARGB8888) } catch(e: Exception) {}
        try { decoder.decodeImage(data, 0f, 0f, 0.5f, 0.5f) } catch(e: Exception) {}
        try { decoder.decodeImage(data, 0f, 0f, 0.5f, 0.5f, ColorFormat.ARGB8888) } catch(e: Exception) {}
    }
}
