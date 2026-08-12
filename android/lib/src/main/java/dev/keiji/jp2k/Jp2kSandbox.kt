package dev.keiji.jp2k

import android.content.Context
import android.util.Log
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
    @JvmStatic
    fun createIsolate(
        sandbox: JavaScriptSandbox,
        maxHeapSizeBytes: Long,
        maxEvaluationReturnSizeBytes: Int,
    ): JavaScriptIsolate {
        val params = IsolateStartupParameters()
        if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
            params.maxHeapSizeBytes = maxHeapSizeBytes
        }
        if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
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
    @JvmStatic
    fun setupConsoleCallback(
        isolate: JavaScriptIsolate,
        sandbox: JavaScriptSandbox,
        executor: Executor,
        tag: String
    ) {
        if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING)) {
            isolate.setConsoleCallback(executor) { consoleMessage ->
                Log.v(tag, consoleMessage.message)
            }
        }
    }

    private val FEATURE_FLAGS = listOf(
        JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING,
        JavaScriptSandbox.JS_FEATURE_EVALUATE_FROM_FD,
        JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT,
        JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
        JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
        JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS,
        JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN,
        JavaScriptSandbox.JS_FEATURE_WASM_COMPILATION,
        JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER,
    )

    /**
     * Logs the support status of JavaScriptSandbox feature flags.
     *
     * @param sandbox The [JavaScriptSandbox] instance.
     * @param logger Function used to output each log line.
     */
    @JvmStatic
    fun logFeatureFlags(sandbox: JavaScriptSandbox, logger: (String) -> Unit) {
        FEATURE_FLAGS.forEach { feature ->
            val supported = sandbox.isFeatureSupported(feature)
            logger("$feature: $supported")
        }
    }
}
