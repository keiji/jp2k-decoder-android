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
     * Feature flag constant for Message Ports support.
     */
    const val FEATURE_MESSAGE_PORTS: String = JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS

    /**
     * Feature flag constant for direct binary transfer (provide/consume array buffer) support.
     */
    const val FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER: String = JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER

    /**
     * Feature flag constant for evaluating JavaScript without IPC transaction size limits.
     */
    const val FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT: String = JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT

    /**
     * Feature flag constant for configuring maximum isolate heap size.
     */
    const val FEATURE_ISOLATE_MAX_HEAP_SIZE: String = JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE

    /**
     * Feature flag constant for console messaging callbacks.
     */
    const val FEATURE_CONSOLE_MESSAGING: String = JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING

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
     * Disables the Message Ports feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableMessagePortsForTesting() = disableFeatureForTesting(FEATURE_MESSAGE_PORTS)

    /**
     * Disables the direct binary transfer (provide/consume array buffer) feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableProvideConsumeArrayBufferForTesting() = disableFeatureForTesting(FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)

    /**
     * Disables the evaluation without transaction limit feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableEvaluateWithoutTransactionLimitForTesting() = disableFeatureForTesting(FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)

    /**
     * Disables the isolate max heap size configuration feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableIsolateMaxHeapSizeForTesting() = disableFeatureForTesting(FEATURE_ISOLATE_MAX_HEAP_SIZE)

    /**
     * Disables the console messaging callback feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableConsoleMessagingForTesting() = disableFeatureForTesting(FEATURE_CONSOLE_MESSAGING)

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
     * Enables the Message Ports feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableMessagePortsForTesting() = enableFeatureForTesting(FEATURE_MESSAGE_PORTS)

    /**
     * Enables the direct binary transfer (provide/consume array buffer) feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableProvideConsumeArrayBufferForTesting() = enableFeatureForTesting(FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)

    /**
     * Enables the evaluation without transaction limit feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableEvaluateWithoutTransactionLimitForTesting() = enableFeatureForTesting(FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)

    /**
     * Enables the isolate max heap size configuration feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableIsolateMaxHeapSizeForTesting() = enableFeatureForTesting(FEATURE_ISOLATE_MAX_HEAP_SIZE)

    /**
     * Enables the console messaging callback feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableConsoleMessagingForTesting() = enableFeatureForTesting(FEATURE_CONSOLE_MESSAGING)

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
