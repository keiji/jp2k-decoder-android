package dev.keiji.jp2k

/**
 * Enum representing possible errors during JPEG 2000 decoding.
 *
 * @property code The integer code corresponding to the error.
 */
enum class Jp2kError(val code: Int) {
    /** No error. */
    None(0),

    /** Error related to the image header. */
    Header(-1),

    /** Input data size is invalid. */
    InputDataSize(-2),

    /** Pixel data size is invalid or mismatches expectations. */
    PixelDataSize(-3),

    /** Generic decoding error. */
    Decode(-4),

    /** Cache data missing error. */
    CacheDataMissing(-10),

    /** Unknown error. */
    Unknown(Int.MIN_VALUE);

    companion object {
        /**
         * Returns the [Jp2kError] corresponding to the given integer code.
         *
         * @param code The error code.
         * @return The matching [Jp2kError], or [Unknown] if not found.
         */
        fun fromInt(code: Int): Jp2kError {
            return entries.find { it.code == code } ?: Unknown
        }
    }
}

/**
 * Exception thrown when a JPEG 2000 decoding error occurs.
 *
 * @property error The specific [Jp2kError] that caused the exception.
 */
class Jp2kException(val error: Jp2kError, message: String? = null) : Exception(message ?: "Error code: ${error.code}")
