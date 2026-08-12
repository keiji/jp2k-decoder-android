package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS

private const val SCRIPT_CONVERTER = """
            (() => {
                const byteToHex = new Array(256);
                for (let i = 0; i < 256; i++) {
                    byteToHex[i] = (i < 16 ? "0" : "") + i.toString(16);
                }
                const hexToByte = new Int32Array(256);
                for (let i = 0; i < 256; i++) {
                    hexToByte[i] = -1;
                }
                for (let i = 0; i < 10; i++) hexToByte[48 + i] = i;
                for (let i = 0; i < 6; i++) {
                    hexToByte[97 + i] = 10 + i;
                    hexToByte[65 + i] = 10 + i;
                }

                globalThis.bytesToHex = function(bytes) {
                    let hex = "";
                    for (let i = 0; i < bytes.length; i++) {
                        hex += byteToHex[bytes[i]];
                    }
                    return hex;
                };

                globalThis.hexToBytes = function(hex) {
                    const bytes = new Uint8Array(hex.length / 2);
                    for (let i = 0, j = 0; i < hex.length; i += 2, j++) {
                        bytes[j] = (hexToByte[hex.charCodeAt(i)] << 4) | hexToByte[hex.charCodeAt(i + 1)];
                    }
                    return bytes;
                };

                globalThis.encodePayload = globalThis.bytesToHex;
                globalThis.decodePayload = globalThis.hexToBytes;
            })();
"""

/**
 * Channel that Hex-encodes binary data and passes it as a JS string.
 *
 * @see JSDataChannel
 */
internal class HexDataChannel : JSDataChannel {
    override val name: String = "HexDataChannel"
    override val isStringMediated: Boolean = true
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
        val chars = CharArray(data.size * 2)
        val hexSymbols = "0123456789abcdef".toCharArray()
        for (i in data.indices) {
            val v = data[i].toInt() and 0xFF
            chars[i * 2] = hexSymbols[v ushr 4]
            chars[i * 2 + 1] = hexSymbols[v and 0x0F]
        }
        return String(chars)
    }

    override fun decodePayload(encoded: String): ByteArray {
        val len = encoded.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((hexToInt(encoded[i]) shl 4) or hexToInt(encoded[i + 1])).toByte()
        }
        return data
    }

    private fun hexToInt(c: Char): Int {
        return when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw IllegalArgumentException("Invalid hex char: $c")
        }
    }

    override val jsConverterScript: String
        get() = SCRIPT_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToHex"

    override val jsDecodeFunctionName: String
        get() = "hexToBytes"
}
