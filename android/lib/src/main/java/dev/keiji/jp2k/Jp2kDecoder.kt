package dev.keiji.jp2k

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * JPEG 2000 Decoder class using WebAssembly via Android JavaScriptEngine.
 *
 * This class handles the initialization of the JavaScript sandbox, loading the WebAssembly module,
 * and decoding JPEG 2000 images.
 *
 * @param config The configuration object for the decoder.
 */
class Jp2kDecoder(
    config: Config = Config(),
) : AutoCloseable {
    private val jp2kDecoderAsync = Jp2kDecoderAsync(config = config)

    /**
     * Initializes the decoder.
     *
     * This method must be called before using [decodeImage]. It initializes the JavaScript sandbox
     * and loads the WebAssembly module.
     *
     * @param context The Android Context.
     * @throws Exception If initialization fails.
     */
    suspend fun init(context: Context) = suspendCancellableCoroutine { continuation ->
        jp2kDecoderAsync.init(context, object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                continuation.resume(result)
            }

            override fun onError(error: Exception) {
                continuation.resumeWithException(error)
            }
        })
    }

    /**
     * Decodes a JPEG 2000 image.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @param colorFormat The desired output color format. Defaults to [ColorFormat.ARGB8888].
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        j2kData: ByteArray,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
    ): Bitmap = suspendCancellableCoroutine { continuation ->
        jp2kDecoderAsync.decodeImage(j2kData, colorFormat, object : Callback<Bitmap> {
            override fun onSuccess(result: Bitmap) {
                continuation.resume(result)
            }

            override fun onError(error: Exception) {
                continuation.resumeWithException(error)
            }
        })
    }

    /**
     * Releases resources held by the decoder.
     *
     * This closes the JavaScript isolate. It should be called when the decoder is no longer needed.
     */
    fun release() {
        jp2kDecoderAsync.release()
    }

    override fun close() {
        release()
    }
}
