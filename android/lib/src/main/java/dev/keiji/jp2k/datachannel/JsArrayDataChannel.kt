package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS

private const val SCRIPT_CONVERTER = """
            globalThis.bytesToArray = function(bytes) {
                return "[" + bytes.join(",") + "]";
            };

            globalThis.arrayToBytes = function(arrayString) {
                if (!arrayString || arrayString.length <= 2) return new Uint8Array(0);
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
    override val name: String = "JsArrayDataChannel"
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
        if (data.isEmpty()) return "[]"
        val sb = StringBuilder(data.size * 4 + 2)
        sb.append("[")
        for (i in data.indices) {
            sb.append(data[i].toInt() and 0xFF)
            if (i < data.lastIndex) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    override fun decodePayload(encoded: String): ByteArray {
        val trimmed = encoded.trim()
        if (trimmed.length <= 2) return ByteArray(0)

        val data = ByteArray(trimmed.length / 2)
        var dataIdx = 0
        
        var currentNum = 0
        var hasNum = false
        for (i in 1 until trimmed.length - 1) { // Skip '[' and ']'
            val c = trimmed[i]
            if (c == ',') {
                if (hasNum) {
                    data[dataIdx++] = currentNum.toByte()
                    currentNum = 0
                    hasNum = false
                }
            } else if (c in '0'..'9') {
                currentNum = currentNum * 10 + (c - '0')
                hasNum = true
            }
        }
        if (hasNum) {
            data[dataIdx++] = currentNum.toByte()
        }
        
        return data.copyOf(dataIdx)
    }

    override val jsConverterScript: String
        get() = SCRIPT_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToArray"

    override val jsDecodeFunctionName: String
        get() = "arrayToBytes"
}
