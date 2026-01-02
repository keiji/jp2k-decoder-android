package dev.keiji.jp2k

/**
 * Configuration class for Jp2kDecoder.
 *
 * @param maxPixels The maximum number of pixels allowed in the decoded image.
 *                  Defaults to [DEFAULT_MAX_PIXELS].
 * @param maxHeapSizeBytes The maximum size of the heap in bytes allowed for the JavaScript sandbox.
 *                         Defaults to [DEFAULT_MAX_HEAP_SIZE_BYTES].
 * @param maxEvaluationReturnSizeBytes The maximum size of the return value in bytes from JavaScript evaluation.
 *                                     Defaults to [DEFAULT_MAX_EVALUATION_RETURN_SIZE_BYTES].
 * @param logLevel The logging level (e.g., Log.DEBUG, Log.INFO). If null, logging is disabled.
 */
data class Config(
    val maxPixels: Int = DEFAULT_MAX_PIXELS,
    val maxHeapSizeBytes: Long = DEFAULT_MAX_HEAP_SIZE_BYTES,
    val maxEvaluationReturnSizeBytes: Int = DEFAULT_MAX_EVALUATION_RETURN_SIZE_BYTES,
    val logLevel: Int? = null
)
