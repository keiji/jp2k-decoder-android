package dev.keiji.jp2k

import android.graphics.Bitmap

/**
 * Enumeration of supported color formats for decoding.
 *
 * @property id The internal ID used by the native decoder.
 * @property bytesPerPixel The number of bytes per pixel.
 * @property bitmapConfig The corresponding [Bitmap.Config].
 */
enum class ColorFormat(
    val id: Int,
    val bytesPerPixel: Int,
    val bitmapConfig: Bitmap.Config,
) {
    /**
     * ARGB 8888 format (4 bytes per pixel).
     */
    ARGB_8888(0, 4, Bitmap.Config.ARGB_8888),

    /**
     * RGB 565 format (2 bytes per pixel).
     */
    RGB_565(1, 2, Bitmap.Config.RGB_565),
}
