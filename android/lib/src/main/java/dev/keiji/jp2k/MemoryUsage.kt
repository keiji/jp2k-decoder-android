package dev.keiji.jp2k

/**
 * Data class representing memory usage statistics of the JavaScript/WASM environment.
 *
 * Note: JS heap statistics ([jsHeapSizeLimit], [totalJSHeapSize], [usedJSHeapSize]) are retrieved
 * via the non-standard `performance.memory` API (or `console.memory`).
 *
 * Source: https://developer.mozilla.org/en-US/docs/Web/API/Performance/memory
 *
 * This API is Chrome/V8 specific and is not guaranteed to be available in all environments.
 * In the Android JavaScriptEngine sandbox, these values may be null if the environment does not expose this API.
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
