package dev.keiji.jp2k

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/**
 * Singleton object for managing the JavaScriptSandbox instance.
 *
 * This ensures that the JavaScriptSandbox connection is reused across the application.
 */
object Jp2kSandbox {
    private var sandboxFuture: ListenableFuture<JavaScriptSandbox>? = null
    private val lock = Any()

    /**
     * Retrieves the shared [JavaScriptSandbox] instance asynchronously.
     *
     * @param context The Android Context (will use Application Context internally).
     * @return A [ListenableFuture] that resolves to the [JavaScriptSandbox].
     */
    @JvmStatic
    fun get(context: Context): ListenableFuture<JavaScriptSandbox> {
        synchronized(lock) {
            if (sandboxFuture == null) {
                sandboxFuture =
                    JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext)
            }
            return sandboxFuture!!
        }
    }

    /**
     * Creates a new [JavaScriptIsolate] with the specified configuration.
     *
     * @param sandbox The [JavaScriptSandbox] instance.
     * @param maxHeapSizeBytes The maximum heap size for the isolate (if supported).
     * @param maxEvaluationReturnSizeBytes The maximum return size for evaluation (if supported).
     * @return A new [JavaScriptIsolate] instance.
     */
    @SuppressLint("RequiresFeature")
    @JvmStatic
    fun createIsolate(
        sandbox: JavaScriptSandbox,
        maxHeapSizeBytes: Long,
        maxEvaluationReturnSizeBytes: Int,
    ): JavaScriptIsolate {
        val params = IsolateStartupParameters()
        if (JavaScriptEngineEnvironment.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
            params.maxHeapSizeBytes = maxHeapSizeBytes
        }
        if (JavaScriptEngineEnvironment.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
            params.maxEvaluationReturnSizeBytes = maxEvaluationReturnSizeBytes
        }
        return sandbox.createIsolate(params)
    }

    /**
     * Sets up a console callback for the isolate to log messages to Android Logcat.
     *
     * @param isolate The [JavaScriptIsolate] to configure.
     * @param sandbox The [JavaScriptSandbox] instance (used to check feature support).
     * @param executor The executor on which the callback will be invoked.
     * @param tag The tag to use for logging.
     */
    @SuppressLint("RequiresFeature")
    @JvmStatic
    fun setupConsoleCallback(
        isolate: JavaScriptIsolate,
        sandbox: JavaScriptSandbox,
        executor: Executor,
        tag: String,
    ) {
        if (JavaScriptEngineEnvironment.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING)) {
            isolate.setConsoleCallback(executor) { consoleMessage ->
                Log.v(tag, consoleMessage.message)
            }
        }
    }

    /**
     * Checks whether the specified [feature] is supported by the provided [sandbox] in the current environment.
     *
     * @param sandbox The [JavaScriptSandbox] instance.
     * @param feature The feature name constant.
     * @return `true` if supported; `false` otherwise.
     */
    @JvmStatic
    fun isFeatureSupported(sandbox: JavaScriptSandbox, feature: String): Boolean {
        return JavaScriptEngineEnvironment.isFeatureSupported(sandbox, feature)
    }

    /**
     * Disables the specified [feature] for testing purposes.
     *
     * @param feature The feature name constant.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableFeatureForTesting(feature: String) {
        JavaScriptEngineEnvironment.disableFeatureForTesting(feature)
    }

    /**
     * Disables the Message Ports feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableMessagePortsForTesting() {
        JavaScriptEngineEnvironment.disableMessagePortsForTesting()
    }

    /**
     * Disables the direct binary transfer (provide/consume array buffer) feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableProvideConsumeArrayBufferForTesting() {
        JavaScriptEngineEnvironment.disableProvideConsumeArrayBufferForTesting()
    }

    /**
     * Disables the evaluation without transaction limit feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableEvaluateWithoutTransactionLimitForTesting() {
        JavaScriptEngineEnvironment.disableEvaluateWithoutTransactionLimitForTesting()
    }

    /**
     * Disables the isolate max heap size configuration feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableIsolateMaxHeapSizeForTesting() {
        JavaScriptEngineEnvironment.disableIsolateMaxHeapSizeForTesting()
    }

    /**
     * Disables the console messaging callback feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun disableConsoleMessagingForTesting() {
        JavaScriptEngineEnvironment.disableConsoleMessagingForTesting()
    }

    /**
     * Enables a previously disabled [feature] for testing purposes.
     *
     * @param feature The feature name constant.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableFeatureForTesting(feature: String) {
        JavaScriptEngineEnvironment.enableFeatureForTesting(feature)
    }

    /**
     * Enables the Message Ports feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableMessagePortsForTesting() {
        JavaScriptEngineEnvironment.enableMessagePortsForTesting()
    }

    /**
     * Enables the direct binary transfer (provide/consume array buffer) feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableProvideConsumeArrayBufferForTesting() {
        JavaScriptEngineEnvironment.enableProvideConsumeArrayBufferForTesting()
    }

    /**
     * Enables the evaluation without transaction limit feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableEvaluateWithoutTransactionLimitForTesting() {
        JavaScriptEngineEnvironment.enableEvaluateWithoutTransactionLimitForTesting()
    }

    /**
     * Enables the isolate max heap size configuration feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableIsolateMaxHeapSizeForTesting() {
        JavaScriptEngineEnvironment.enableIsolateMaxHeapSizeForTesting()
    }

    /**
     * Enables the console messaging callback feature for testing purposes.
     */
    @VisibleForTesting
    @JvmStatic
    fun enableConsoleMessagingForTesting() {
        JavaScriptEngineEnvironment.enableConsoleMessagingForTesting()
    }

    /**
     * Resets all test feature overrides.
     */
    @VisibleForTesting
    @JvmStatic
    fun resetFeaturesForTesting() {
        JavaScriptEngineEnvironment.resetForTesting()
    }
}
