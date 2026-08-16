package dev.keiji.jp2k

import androidx.annotation.VisibleForTesting
import androidx.javascriptengine.JavaScriptSandbox

/**
 * Manages environment-dependent configurations, limits, and feature flags for the JavaScript engine.
 *
 * This singleton provides central access to system constraints (such as Binder transaction limits
 * and WebAssembly memory limits) and feature support checks for [JavaScriptSandbox].
 * Testing utilities are provided to simulate constrained or degraded environments.
 */
object JavaScriptEngineEnvironment {

    /**
     * Default maximum chunk size in bytes / characters for safe transfer across Android Binder transactions.
     * 256KB: Safely below the 1MB shared Binder buffer limit.
     */
    const val DEFAULT_BINDER_TRANSACTION_MAX_CHUNK_SIZE_BYTES: Int = 256 * 1024

    /**
     * Default maximum addressable linear memory for WebAssembly 32-bit (4GB).
     */
    const val DEFAULT_WASM_MAX_MEMORY_BYTES: Long = 4L * 1024 * 1024 * 1024L

    /**
     * The maximum chunk size in bytes for Binder transactions.
     */
    @Volatile
    var binderTransactionMaxChunkSizeBytes: Int = DEFAULT_BINDER_TRANSACTION_MAX_CHUNK_SIZE_BYTES

    /**
     * The maximum addressable memory size in bytes for WebAssembly execution.
     */
    @Volatile
    var wasmMaxMemoryBytes: Long = DEFAULT_WASM_MAX_MEMORY_BYTES

    private val disabledFeatures = mutableSetOf<String>()
    private val lock = Any()

    /**
     * Checks whether the specified [feature] is supported by the provided [sandbox] in the current environment.
     *
     * If the feature has been disabled via [disableFeatureForTesting], this method returns `false`.
     *
     * @param sandbox The [JavaScriptSandbox] instance to query.
     * @param feature The feature name constant (e.g. `JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS`).
     * @return `true` if the feature is supported and not disabled for testing; `false` otherwise.
     */
    @JvmStatic
    fun isFeatureSupported(sandbox: JavaScriptSandbox, feature: String): Boolean {
        synchronized(lock) {
            if (disabledFeatures.contains(feature)) {
                return false
            }
        }
        return sandbox.isFeatureSupported(feature)
    }

    /**
     * Disables the specified [feature] for testing purposes.
     *
     * @param feature The feature name constant to disable.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableFeatureForTesting(feature: String) {
        synchronized(lock) {
            disabledFeatures.add(feature)
        }
    }

    /**
     * Enables a previously disabled [feature] for testing purposes.
     *
     * @param feature The feature name constant to re-enable.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableFeatureForTesting(feature: String) {
        synchronized(lock) {
            disabledFeatures.remove(feature)
        }
    }

    /**
     * Checks if the specified [feature] is currently marked as disabled for testing.
     *
     * @param feature The feature name constant.
     * @return `true` if the feature is disabled for testing; `false` otherwise.
     */
    @VisibleForTesting
    @JvmStatic
    fun isFeatureDisabledForTesting(feature: String): Boolean {
        synchronized(lock) {
            return disabledFeatures.contains(feature)
        }
    }

    /**
     * Resets all test overrides (disabled features, chunk sizes, memory limits) to their defaults.
     */
    @VisibleForTesting
    @JvmStatic
    fun resetForTesting() {
        synchronized(lock) {
            disabledFeatures.clear()
            binderTransactionMaxChunkSizeBytes = DEFAULT_BINDER_TRANSACTION_MAX_CHUNK_SIZE_BYTES
            wasmMaxMemoryBytes = DEFAULT_WASM_MAX_MEMORY_BYTES
        }
    }
}
