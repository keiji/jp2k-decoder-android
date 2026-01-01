package dev.keiji.jp2k

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture

object Jp2kSandbox {
    // 512MB: Sufficient to decode large high-resolution images (e.g. 4000x3000) which may require significant internal buffer space.
    private const val MAX_HEAP_SIZE_BYTES = 512L * 1024 * 1024

    // 256MB: Sufficient to return the decoded pixel data (e.g. 4000x3000 * 4 bytes/pixel ~= 48MB) plus overhead as a Hex string or byte array.
    private const val MAX_EVALUATION_RETURN_SIZE_BYTES = 256 * 1024 * 1024

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
            params.maxHeapSizeBytes = MAX_HEAP_SIZE_BYTES
        }
        if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
            params.maxEvaluationReturnSizeBytes = MAX_EVALUATION_RETURN_SIZE_BYTES
        }
        return sandbox.createIsolate(params)
    }
}
