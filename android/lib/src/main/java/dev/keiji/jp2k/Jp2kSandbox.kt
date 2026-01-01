package dev.keiji.jp2k

import android.content.Context
import android.util.Log
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

object Jp2kSandbox {
    private var sandboxFuture: ListenableFuture<JavaScriptSandbox>? = null
    private val lock = Any()

    fun get(context: Context): ListenableFuture<JavaScriptSandbox> {
        synchronized(lock) {
            if (sandboxFuture == null) {
                sandboxFuture =
                    JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext)
            }
            return sandboxFuture!!
        }
    }

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
}
