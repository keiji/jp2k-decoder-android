package dev.keiji.jp2k

/**
 * Enum representing the color format for the decoded image.
 *
 * @property id The integer identifier for the color format passed to the decoder.
 */
enum class ColorFormat(val id: Int) {
    /** RGB 565 format. */
    RGB565(565),

    /** ARGB 8888 format. */
    ARGB8888(8888),
}
