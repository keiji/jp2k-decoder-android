package dev.keiji.jp2k

enum class Jp2kError(val code: Int) {
    None(0),
    Header(-1),
    TooLarge(-2),
    Decode(-3),
    Unknown(Int.MIN_VALUE);

    companion object {
        fun fromInt(code: Int): Jp2kError {
            return entries.find { it.code == code } ?: Unknown
        }
    }
}

class Jp2kException(val error: Jp2kError, message: String? = null) : Exception(message ?: "Error code: ${error.code}")
