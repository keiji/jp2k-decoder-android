package dev.keiji.jp2k

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture

object Jp2kSandbox {
    private var sandboxFuture: ListenableFuture<JavaScriptSandbox>? = null
    private val lock = Any()

    fun get(context: Context): ListenableFuture<JavaScriptSandbox> {
        synchronized(lock) {
            if (sandboxFuture == null) {
                sandboxFuture = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext)
            }
            return sandboxFuture!!
        }
    }

    fun createIsolate(sandbox: JavaScriptSandbox): JavaScriptIsolate {
        val params = IsolateStartupParameters()
        if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
            params.maxHeapSizeBytes = 512 * 1024 * 1024
        }
        if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
            params.maxEvaluationReturnSizeBytes = 256 * 1024 * 1024
        }
        return sandbox.createIsolate(params)
    }
}
