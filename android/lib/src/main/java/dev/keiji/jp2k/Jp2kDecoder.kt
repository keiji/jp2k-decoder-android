package dev.keiji.jp2k

import android.content.Context
import android.graphics.Bitmap

/**
 * A class for decoding JPEG2000 images using a suspendable API.
 *
 * @property context The Android Context.
 */
class Jp2kDecoder(context: Context) {

    /**
     * Initializes the decoder asynchronously.
     *
     * @return True if initialization succeeds, false otherwise.
     */
    suspend fun init(): Boolean {
        return false
    }

    /**
     * Decodes a JPEG2000 image to a [Bitmap].
     *
     * @param data The input JPEG2000 byte array.
     * @return The decoded [Bitmap], or null if decoding fails.
     */
    suspend fun decodeImage(data: ByteArray): Bitmap? {
        return null
    }

    /**
     * Releases resources held by the decoder.
     */
    fun release() {}
}
