package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS

private const val SCRIPT_CONVERTER = """
            globalThis.bytesToArray = function(bytes) {
                return "[" + Array.from(bytes).join(",") + "]";
            };

            globalThis.arrayToBytes = function(arrayString) {
                if (!arrayString || arrayString.length === 0) return new Uint8Array(0);
                const arr = JSON.parse(arrayString);
                return new Uint8Array(arr);
            };

            globalThis.encodePayload = globalThis.bytesToArray;
            globalThis.decodePayload = globalThis.arrayToBytes;
"""

/**
 * Channel that encodes binary data as a JS array string and passes it to JS.
 *
 * @see JSDataChannel
 */
internal class JsArrayDataChannel : JSDataChannel {
    override fun init(sandbox: JavaScriptSandbox) {
        // No-op — JsArray works on all devices
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        val encoded = encodePayload(wasmBytes)
        return "arrayToBytes('$encoded')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData)
        return "(async () => { globalThis.j2kData = globalThis.arrayToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        return data.joinToString(separator = ",", prefix = "[", postfix = "]") { (it.toInt() and 0xFF).toString() }
    }

    override fun decodePayload(encoded: String): ByteArray {
        val trimmed = encoded.trim().removePrefix("[").removeSuffix("]")
        if (trimmed.isEmpty()) return ByteArray(0)
        val parts = trimmed.split(",")
        val bytes = ByteArray(parts.size)
        for (i in parts.indices) {
            bytes[i] = parts[i].trim().toInt().toByte()
        }
        return bytes
    }

    override val jsConverterScript: String
        get() = SCRIPT_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToArray"

    override val jsDecodeFunctionName: String
        get() = "arrayToBytes"
}
