package dev.keiji.jp2k

/**
 * Data class representing performance metrics for JPEG 2000 decoding operations.
 *
 * @property inputDataSizeBytes Size of input raw JPEG 2000 byte array in bytes.
 * @property dataTransferTimeMs Time spent transferring data or script execution to JavaScript environment in milliseconds.
 * @property jsDecodeTimeMs Time spent decoding payload to Uint8Array inside JavaScript in milliseconds.
 * @property wasmProcessingTimeMs Time spent executing WebAssembly JPEG 2000 decoding function in milliseconds.
 * @property jsEncodeTimeMs Time spent encoding decoded raw bitmap buffer in JavaScript in milliseconds.
 * @property outputDataSizeBytes Size of output decoded bitmap bytes in bytes.
 * @property wasmHeapSizeBytes WASM memory buffer size in bytes after decoding.
 * @property totalProcessingTimeMs Total time taken for decoding operation from JVM start to end in milliseconds.
 */
data class PerformanceMetrics(
    val inputDataSizeBytes: Long,
    val dataTransferTimeMs: Double,
    val jsDecodeTimeMs: Double,
    val wasmProcessingTimeMs: Double,
    val jsEncodeTimeMs: Double,
    val outputDataSizeBytes: Long,
    val wasmHeapSizeBytes: Long,
    val totalProcessingTimeMs: Double,
)
