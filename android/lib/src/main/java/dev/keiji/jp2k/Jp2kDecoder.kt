package dev.keiji.jp2k

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A suspendable decoder for JPEG2000 images.
 *
 * This class provides a Coroutine-friendly API for decoding JPEG2000 images,
 * wrapping the callback-based [Jp2kDecoderAsync].
 *
 * @param context The Android Context.
 * @param config The configuration for the decoder.
 */
class Jp2kDecoder(
    context: Context,
    config: Config = Config(),
) {
    private val jp2kDecoderAsync = Jp2kDecoderAsync(context, config)

    /**
     * Initializes the decoder asynchronously.
     *
     * @return Unit when initialization is complete.
     */
    suspend fun init() = suspendCancellableCoroutine { continuation ->
        jp2kDecoderAsync.initAsync(object : Callback<Unit> {
            override fun onSuccess(result: Unit) {
                continuation.resume(result)
            }

            override fun onError(error: Exception) {
                continuation.resumeWithException(error)
            }
        })
    }

    /**
     * Decodes a JPEG2000 image to a [Bitmap].
     *
     * @param bytes The input JPEG2000 byte array.
     * @param colorFormat The desired output color format.
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        bytes: ByteArray,
        colorFormat: ColorFormat = ColorFormat.ARGB_8888,
    ): Bitmap = suspendCancellableCoroutine { continuation ->
        jp2kDecoderAsync.decodeImageAsync(bytes, colorFormat, object : Callback<Bitmap> {
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
     */
    fun release() {
        jp2kDecoderAsync.release()
    }
}
