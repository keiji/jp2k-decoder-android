package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import dev.keiji.jp2k.SCRIPT_BYTES_HEX_CONVERTER

/**
 * Channel that Hex-encodes binary data and passes it as a JS string.
 *
 * @see JSDataChannel
 */
internal class HexDataChannel : JSDataChannel {
    override fun init(sandbox: JavaScriptSandbox) {
        // No-op — Hex works on all devices
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        val encoded = encodePayload(wasmBytes)
        return "hexToBytes('$encoded')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData)
        return "(async () => { globalThis.j2kData = globalThis.hexToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val i = b.toInt() and 0xFF
            if (i < 16) sb.append('0')
            sb.append(Integer.toHexString(i))
        }
        return sb.toString()
    }

    override fun decodePayload(encoded: String): ByteArray {
        val len = encoded.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(encoded[i], 16) shl 4) + Character.digit(encoded[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    override val jsConverterScript: String
        get() = SCRIPT_BYTES_HEX_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToHex"

    override val jsDecodeFunctionName: String
        get() = "hexToBytes"
}
