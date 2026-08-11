@file:Suppress("RequiresFeature")

package dev.keiji.jp2k

import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.JavaScriptIsolate
import java.util.Base64

/**
 * Abstraction for transferring binary data (WASM, J2K image) to the JavaScript sandbox.
 *
 * Two implementations:
 * - [ProvidedNamedDataChannel]: uses [JavaScriptIsolate.provideNamedData] for direct binary transfer
 * - [Base64DataChannel]: Base64-encodes data to a JS string (fallback for unsupported devices)
 *
 * Created at init time via [createDataChannel] — channel selection is deterministic
 * based on [JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER] support.
 *
 * Callers do NOT know or care which implementation is used; they just invoke
 * [getWasmExpression] or [getJ2KExpression].
 */
internal interface JSDataChannel {

      /**
       * Initializes the channel with the sandbox.
       * Only [ProvidedNamedDataChannel] uses this; [Base64DataChannel] is a no-op.
       */
    fun init(sandbox: JavaScriptSandbox)

      /**
       * Provides WASM binary data and returns a JS expression that retrieves it.
       */
    fun getWasmExpression(isolate: JavaScriptIsolate, wasmBytes: ByteArray): String

      /**
       * Provides J2K data and returns a JS statement that assigns it to `globalThis.j2kData`.
       */
    fun getJ2KExpression(isolate: JavaScriptIsolate, j2kData: ByteArray): String
}

/**
 * Channel that uses [JavaScriptIsolate.provideNamedData] for direct binary data transfer.
 *
 * @see JSDataChannel
  */
internal class ProvidedNamedDataChannel : JSDataChannel {
     @Volatile
    private var sandbox: JavaScriptSandbox? = null

    override fun init(sandbox: JavaScriptSandbox) {
        require(sandbox.isFeatureSupported(
            JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER
         )) {
             "JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER not supported"
          }
        this.sandbox = sandbox
      }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
      ): String {
        isolate.provideNamedData(PROVIDED_WASM_DATA, wasmBytes)
        return "globalThis.transferFromProvidedNamedData('$PROVIDED_WASM_DATA')"
      }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
      ): String {
        isolate.provideNamedData(PROVIDED_J2K_DATA, j2kData)
        return "(async () => { globalThis.j2kData = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return '$INTERNAL_RESULT_SUCCESS'; })()"
      }
}

/**
 * Channel that Base64-encodes binary data and passes it as a JS string.
 *
 * This is the fallback for devices that do not support
 * [JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER].
 *
 * @see JSDataChannel
 */
internal class Base64DataChannel : JSDataChannel {
    override fun init(sandbox: JavaScriptSandbox) {
          // No-op — Base64 works on all devices
      }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
      ): String {
        val encoded = Base64.getEncoder().encodeToString(wasmBytes)
        return "base64ToBytes('$encoded')"
      }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
      ): String {
        val encoded = Base64.getEncoder().encodeToString(j2kData)
        return "(async () => { globalThis.j2kData = globalThis.base64ToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
      }
}

/**
 * Creates the appropriate [JSDataChannel] based on feature support.
 *
 * Checks [JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER] using the provided sandbox
 * and initializes the channel via its [JSDataChannel.init] method.
 *
 * @return [ProvidedNamedDataChannel] if supported, otherwise [Base64DataChannel].
 */
internal fun createDataChannel(sandbox: JavaScriptSandbox): JSDataChannel {
    return if (sandbox.isFeatureSupported(
        JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER
     )) {
        ProvidedNamedDataChannel().also { it.init(sandbox) }
      } else {
        Base64DataChannel().also { it.init(sandbox) }
      }
}
