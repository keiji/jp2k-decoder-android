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
     * The decoder is terminating.
     */
    Terminating,

    /**
     * The decoder has been terminated and cannot be used anymore.
     */
    Terminated
}
