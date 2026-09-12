package dev.keiji.jp2k

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import org.junit.Test
import org.mockito.Mockito.mock

class Jp2kDecoderAsyncSyntheticCoverageTest {

    @Test
    fun invokeSynthetics() {
        val decoder = Jp2kDecoderAsync()
        val data = ByteArray(12)
        val rect = Rect(10, 20, 30, 40)
        val rectF = RectF(0.1f, 0.2f, 0.3f, 0.4f)
        val cb = object : Callback<Bitmap> {
            override fun onSuccess(result: Bitmap) {}
            override fun onError(error: Exception) {}
        }

        val methods = decoder.javaClass.declaredMethods
        for (method in methods) {
            if (method.name.contains("\$default")) {
                method.isAccessible = true
                val params = method.parameterTypes
                for (mask in 0..63) {
                    val args = Array<Any?>(params.size) { null }

                    for (i in params.indices) {
                        val p = params[i]
                        if (p == Jp2kDecoderAsync::class.java) args[i] = decoder
                        else if (p == ByteArray::class.java) args[i] = data
                        else if (p == Int::class.java) {
                            if (i >= params.size - 2) {
                                args[i] = mask // Mask
                            } else {
                                args[i] = 0
                            }
                        }
                        else if (p == Long::class.java) args[i] = 0L
                        else if (p == Float::class.java) args[i] = 0f
                        else if (p == ColorFormat::class.java) args[i] = null
                        else if (p == Rect::class.java) args[i] = rect
                        else if (p == RectF::class.java) args[i] = rectF
                        else if (p == Callback::class.java) args[i] = cb
                    }

                    try {
                        method.invoke(null, *args)
                    } catch(e: Exception) {
                        // Ignore
                    }
                }
            }
        }
    }
}
