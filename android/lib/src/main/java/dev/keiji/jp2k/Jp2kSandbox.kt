package dev.keiji.jp2k

import android.content.Context
import android.util.Log
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptConsoleCallback
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import java.util.concurrent.Executor

/**
 * Singleton for managing the JavaScript Sandbox connection.
 *
 * This class ensures that the [JavaScriptSandbox] is initialized only once and shared
 * across decoders to optimize resource usage.
 */
internal object Jp2kSandbox {
    private var jsSandbox: JavaScriptSandbox? = null

    /**
     * Retrieves the [JavaScriptSandbox] instance.
     *
     * @param context The Android Context.
     * @return The [JavaScriptSandbox] instance.
     */
    @Synchronized
    fun getInstance(context: Context): JavaScriptSandbox {
        if (jsSandbox == null) {
            jsSandbox = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).get()
        }
        return jsSandbox!!
    }

    /**
     * Creates a new [JavaScriptIsolate] within the sandbox.
     *
     * @return A new [JavaScriptIsolate].
     */
    fun createIsolate(): JavaScriptIsolate {
        val sandbox = jsSandbox ?: throw IllegalStateException("Sandbox not initialized")
        val params = IsolateStartupParameters()
        params.maxHeapSizeBytes = Constants.MAX_HEAP_SIZE_BYTES.toLong()
        params.maxEvaluationReturnSizeBytes = Constants.MAX_EVALUATION_RETURN_SIZE_BYTES
        return sandbox.createIsolate(params).apply {
            // Load WASM and Utils if needed
        }
    }

    /**
     * Sets up a console callback to redirect JS logs to Android Logcat.
     *
     * @param isolate The isolate to attach the callback to.
     * @param executor The executor to run the callback on.
     * @param logLevel The log level.
     */
    fun setupConsoleCallback(isolate: JavaScriptIsolate, executor: Executor, logLevel: Int?) {
        if (logLevel == null) return

        isolate.setConsoleCallback(executor, object : JavaScriptConsoleCallback {
            override fun onConsoleMessage(consoleMessage: androidx.javascriptengine.JavaScriptConsoleCallback.ConsoleMessage) {
                Constants.log(logLevel, Log.INFO, "JS Console: ${consoleMessage.message}")
            }
        })
    }
}
