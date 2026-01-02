package dev.keiji.jp2k

/**
 * Data class representing memory usage statistics of the JavaScript/WASM environment.
 *
 * Note: JS heap statistics ([jsHeapSizeLimit], [totalJSHeapSize], [usedJSHeapSize]) are retrieved
 * via `performance.memory` or `console.memory`. These APIs are non-standard and may be restricted
 * or unavailable in some Android JavaScriptEngine environments (e.g., due to security or privacy configurations).
 * In such cases, these fields will be null.
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
