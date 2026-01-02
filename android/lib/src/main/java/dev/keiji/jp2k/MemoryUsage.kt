package dev.keiji.jp2k

/**
 * Data class representing memory usage statistics of the JavaScript/WASM environment.
 *
 * @property wasmHeapSizeBytes The size of the WASM memory buffer in bytes.
 * @property jsHeapSizeLimit The maximum size of the JS heap in bytes (if available).
 * @property totalJSHeapSize The total size of the JS heap in bytes (if available).
 * @property usedJSHeapSize The used size of the JS heap in bytes (if available).
 */
data class MemoryUsage(
    val wasmHeapSizeBytes: Long,
    val jsHeapSizeLimit: Long? = null,
    val totalJSHeapSize: Long? = null,
    val usedJSHeapSize: Long? = null
)
