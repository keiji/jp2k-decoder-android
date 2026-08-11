package dev.keiji.jp2k

/**
 * Data class representing performance metrics for JPEG 2000 decoding operations.
 *
 * @property inputDataSizeBytes Size of input raw JPEG 2000 byte array in bytes.
 * @property base64TransferTimeMs Time spent transferring/converting Base64 string to JavaScript environment in milliseconds.
 * @property base64DecodeTimeMs Time spent decoding Base64 string to Uint8Array inside JavaScript in milliseconds.
 * @property wasmProcessingTimeMs Time spent executing WebAssembly JPEG 2000 decoding function in milliseconds.
 * @property base64EncodeTimeMs Time spent encoding decoded raw bitmap buffer to Base64 in JavaScript in milliseconds.
 * @property outputDataSizeBytes Size of output decoded bitmap bytes in bytes.
 * @property wasmHeapSizeBytes WASM memory buffer size in bytes after decoding.
 * @property totalProcessingTimeMs Total time taken for decoding operation from JVM start to end in milliseconds.
 */
data class PerformanceMetrics(
    val inputDataSizeBytes: Long,
    val base64TransferTimeMs: Double,
    val base64DecodeTimeMs: Double,
    val wasmProcessingTimeMs: Double,
    val base64EncodeTimeMs: Double,
    val outputDataSizeBytes: Long,
    val wasmHeapSizeBytes: Long,
    val totalProcessingTimeMs: Double,
)
