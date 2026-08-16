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
 * @param logger The logger instance to output log messages. Defaults to [AndroidLogger].
 * @param maxLogLines The maximum number of log lines to output per log message. Messages exceeding this line count will be truncated. Defaults to 10.
 * @param preferDirectBinaryTransfer Whether to prefer direct binary transfer via `JavaScriptIsolate.provideNamedData` when available. This enables more efficient data transfer; if false, string-mediated data transfer is used. Defaults to true.
 * @param binderTransactionMaxChunkSizeBytes The maximum chunk size in bytes for transfer across Android Binder transactions. Defaults to [JavaScriptEngineEnvironment.binderTransactionMaxChunkSizeBytes].
 * @param wasmMaxMemoryBytes The maximum allowable addressable memory size in bytes for WebAssembly execution. Defaults to [JavaScriptEngineEnvironment.wasmMaxMemoryBytes].
 */
data class Config(
    val maxPixels: Int = DEFAULT_MAX_PIXELS,
    val maxHeapSizeBytes: Long = DEFAULT_MAX_HEAP_SIZE_BYTES,
    val maxEvaluationReturnSizeBytes: Int = DEFAULT_MAX_EVALUATION_RETURN_SIZE_BYTES,
    val logLevel: Int? = null,
    val logger: Logger = AndroidLogger,
    val maxLogLines: Int = 10,
    val preferDirectBinaryTransfer: Boolean = true,
    val binderTransactionMaxChunkSizeBytes: Int = JavaScriptEngineEnvironment.binderTransactionMaxChunkSizeBytes,
    val wasmMaxMemoryBytes: Long = JavaScriptEngineEnvironment.wasmMaxMemoryBytes,
)
