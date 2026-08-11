package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import dev.keiji.jp2k.SCRIPT_BYTES_BASE64_CONVERTER
import java.util.Base64

/**
 * Channel that Base64-encodes binary data and passes it as a JS string.
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
        val encoded = encodePayload(wasmBytes)
        return "base64ToBytes('$encoded')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData)
        return "(async () => { globalThis.j2kData = globalThis.base64ToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        return Base64.getEncoder().encodeToString(data)
    }

    override fun decodePayload(encoded: String): ByteArray {
        return Base64.getDecoder().decode(encoded)
    }

    override val jsConverterScript: String
        get() = SCRIPT_BYTES_BASE64_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToBase64"

    override val jsDecodeFunctionName: String
        get() = "base64ToBytes"
}
