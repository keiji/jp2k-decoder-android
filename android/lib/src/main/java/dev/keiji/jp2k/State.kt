package dev.keiji.jp2k

/**
 * Enum representing the state of the decoder.
 */
enum class State {
    /**
     * The decoder is not initialized.
     */
    Uninitialized,

    /**
     * The decoder is currently initializing.
     */
    Initializing,

    /**
     * The decoder is initialized and ready to decode.
     */
    Initialized,

    /**
     * The decoder is currently processing.
     */
    Processing,

    /**
     * The decoder is releasing.
     */
    Releasing,

    /**
     * The decoder has been released and cannot be used anymore.
     */
    Released
}
