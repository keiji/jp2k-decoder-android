@file:Suppress("RequiresFeature")

package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import dev.keiji.jp2k.PROVIDED_J2K_DATA
import dev.keiji.jp2k.PROVIDED_WASM_DATA

/**
 * Channel that uses [JavaScriptIsolate.provideNamedData] for direct binary data transfer.
 *
 * @see JSDataChannel
 */
internal class ProvidedNamedDataChannel : JSDataChannel {
    override val name: String = "ProvidedNamedDataChannel"
    override val isStringMediated: Boolean = false
    @Volatile
    private var sandbox: JavaScriptSandbox? = null

    private val fallbackChannel = Base64DataChannel()

    override fun init(sandbox: JavaScriptSandbox) {
        require(
            sandbox.isFeatureSupported(
                JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER,
            )
        ) {
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

    override fun encodePayload(data: ByteArray): String = fallbackChannel.encodePayload(data)

    override fun decodePayload(encoded: String): ByteArray = fallbackChannel.decodePayload(encoded)

    override val jsConverterScript: String
        get() = fallbackChannel.jsConverterScript

    override val jsEncodeFunctionName: String
        get() = fallbackChannel.jsEncodeFunctionName

    override val jsDecodeFunctionName: String
        get() = fallbackChannel.jsDecodeFunctionName
}
