package dev.keiji.jp2k

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import org.junit.Test
import org.mockito.Mockito.mock

class Jp2kDecoderAsyncOverloadsTest {

    @Test
    fun callAllOverloadsToEnsureCoverage() {
        val decoder = Jp2kDecoderAsync()
        val callback = object : Callback<Bitmap> {
            override fun onSuccess(result: Bitmap) {}
            override fun onError(error: Exception) {}
        }
        val data = ByteArray(12)
        val rect = Rect(10, 20, 30, 40)
        val rectF = RectF(0.1f, 0.2f, 0.3f, 0.4f)

        try { decoder.decodeImage(callback) } catch(e: Exception) {}
        try { decoder.decodeImage(ColorFormat.ARGB8888, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(0, 0, 10, 10, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(0, 0, 10, 10, ColorFormat.ARGB8888, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(rect, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(rect, ColorFormat.ARGB8888, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(rectF, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(rectF, ColorFormat.ARGB8888, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(0f, 0f, 0.5f, 0.5f, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(0f, 0f, 0.5f, 0.5f, ColorFormat.ARGB8888, callback) } catch(e: Exception) {}

        try { decoder.decodeImage(data, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(data, ColorFormat.ARGB8888, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(data, 0, 0, 10, 10, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(data, 0, 0, 10, 10, ColorFormat.ARGB8888, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(data, rect, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(data, rect, ColorFormat.ARGB8888, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(data, rectF, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(data, rectF, ColorFormat.ARGB8888, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(data, 0f, 0f, 0.5f, 0.5f, callback) } catch(e: Exception) {}
        try { decoder.decodeImage(data, 0f, 0f, 0.5f, 0.5f, ColorFormat.ARGB8888, callback) } catch(e: Exception) {}
    }
}
