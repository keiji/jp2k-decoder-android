package dev.keiji.jp2k

/**
 * Data class representing memory usage statistics of the JavaScript/WASM environment.
 *
 * @property wasmHeapSizeBytes The size of the WASM memory buffer in bytes.
 */
data class MemoryUsage(
    val wasmHeapSizeBytes: Long,
)
