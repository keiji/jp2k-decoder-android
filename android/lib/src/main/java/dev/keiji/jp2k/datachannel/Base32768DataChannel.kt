package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS

private const val SCRIPT_CONVERTER = """
            globalThis.bytesToBase32768 = function(bytes) {
                if (!bytes || bytes.length === 0) return "";
                let result = "";
                let bitBuffer = 0;
                let bitCount = 0;
                const len = bytes.length;

                for (let i = 0; i < len; i++) {
                    bitBuffer = (bitBuffer << 8) | (bytes[i] & 0xFF);
                    bitCount += 8;
                    while (bitCount >= 15) {
                        const chunk15 = (bitBuffer >>> (bitCount - 15)) & 0x7FFF;
                        bitCount -= 15;
                        let codePoint;
                        if (chunk15 < 0x1900) {
                            codePoint = 0x3400 + chunk15;
                        } else if (chunk15 < 0x6B00) {
                            codePoint = 0x4E00 + (chunk15 - 0x1900);
                        } else {
                            codePoint = 0xAC00 + (chunk15 - 0x6B00);
                        }
                        result += String.fromCharCode(codePoint);
                    }
                }

                if (bitCount > 0) {
                    const remainingBits = bitBuffer & ((1 << bitCount) - 1);
                    const chunk15 = (remainingBits << (15 - bitCount)) & 0x7FFF;
                    let codePoint;
                    if (chunk15 < 0x1900) {
                        codePoint = 0x3400 + chunk15;
                    } else if (chunk15 < 0x6B00) {
                        codePoint = 0x4E00 + (chunk15 - 0x1900);
                    } else {
                        codePoint = 0xAC00 + (chunk15 - 0x6B00);
                    }
                    result += String.fromCharCode(codePoint);

                    const remBytes = len % 15;
                    if (remBytes > 0) {
                        result += String.fromCharCode(0x2100 + remBytes);
                    }
                }

                return result;
            };

            globalThis.base32768ToBytes = function(str) {
                if (!str || str.length === 0) return new Uint8Array(0);

                let remBytes = 0;
                let mainStrLength = str.length;

                const lastCode = str.charCodeAt(str.length - 1);
                if (lastCode >= 0x2101 && lastCode <= 0x210E) {
                    remBytes = lastCode - 0x2100;
                    mainStrLength = str.length - 1;
                }

                const chunks = new Uint16Array(mainStrLength);
                let chunkCount = 0;
                for (let i = 0; i < mainStrLength; i++) {
                    const code = str.charCodeAt(i);
                    let val15 = -1;
                    if (code >= 0x3400 && code <= 0x4CFF) {
                        val15 = code - 0x3400;
                    } else if (code >= 0x4E00 && code <= 0x9FFF) {
                        val15 = (code - 0x4E00) + 0x1900;
                    } else if (code >= 0xAC00 && code <= 0xC0FF) {
                        val15 = (code - 0xAC00) + 0x6B00;
                    }
                    if (val15 >= 0) {
                        chunks[chunkCount++] = val15;
                    }
                }

                let totalBytes;
                if (remBytes > 0) {
                    const fullBlocks = Math.floor(chunkCount / 8);
                    totalBytes = fullBlocks * 15 + remBytes;
                } else {
                    totalBytes = Math.floor((chunkCount * 15) / 8);
                }

                const out = new Uint8Array(totalBytes);
                let bitBuffer = 0;
                let bitCount = 0;
                let outIdx = 0;

                for (let i = 0; i < chunkCount; i++) {
                    bitBuffer = (bitBuffer << 15) | chunks[i];
                    bitCount += 15;
                    while (bitCount >= 8 && outIdx < totalBytes) {
                        out[outIdx++] = (bitBuffer >>> (bitCount - 8)) & 0xFF;
                        bitCount -= 8;
                    }
                }

                return out;
            };

            globalThis.encodePayload = globalThis.bytesToBase32768;
            globalThis.decodePayload = globalThis.base32768ToBytes;
"""

/**
 * Channel that encodes binary data using Base32768 (15 bits per UTF-16 character)
 * and passes it as a JS string.
 *
 * Base32768 packs 15 bits per character using safe BMP Unicode ranges:
 * - Range 1: U+3400..U+4CFF (CJK Extension A)
 * - Range 2: U+4E00..U+9FFF (CJK Unified Ideographs)
 * - Range 3: U+AC00..U+C0FF (Hangul Syllables)
 *
 * @see JSDataChannel
 */
internal class Base32768DataChannel : JSDataChannel {
    override val name: String = "Base32768DataChannel"
    override fun init(sandbox: JavaScriptSandbox) {
        // No-op — Base32768 works on all devices
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        val encoded = encodePayload(wasmBytes).escapeJs()
        return "base32768ToBytes('$encoded')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData).escapeJs()
        return "(async () => { globalThis.j2kData = globalThis.base32768ToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        if (data.isEmpty()) return ""

        val sb = StringBuilder((data.size * 8) / 15 + 2)
        var bitBuffer = 0
        var bitCount = 0
        val len = data.size

        for (i in 0 until len) {
            bitBuffer = (bitBuffer shl 8) or (data[i].toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= 15) {
                val chunk15 = (bitBuffer ushr (bitCount - 15)) and 0x7FFF
                bitCount -= 15
                sb.append(encodeChunk15(chunk15))
            }
        }

        if (bitCount > 0) {
            val remainingBits = bitBuffer and ((1 shl bitCount) - 1)
            val chunk15 = (remainingBits shl (15 - bitCount)) and 0x7FFF
            sb.append(encodeChunk15(chunk15))

            val remBytes = len % 15
            if (remBytes > 0) {
                sb.append((0x2100 + remBytes).toChar())
            }
        }

        return sb.toString()
    }

    override fun decodePayload(encoded: String): ByteArray {
        if (encoded.isEmpty()) return ByteArray(0)

        var remBytes = 0
        var mainStr = encoded

        val lastCode = encoded.last().code
        if (lastCode in 0x2101..0x210E) {
            remBytes = lastCode - 0x2100
            mainStr = encoded.substring(0, encoded.length - 1)
        }

        val chunks = IntArray(mainStr.length)
        var chunkCount = 0
        for (i in mainStr.indices) {
            val val15 = decodeChar(mainStr[i])
            if (val15 >= 0) {
                chunks[chunkCount++] = val15
            }
        }

        val totalBytes = if (remBytes > 0) {
            val fullBlocks = chunkCount / 8
            fullBlocks * 15 + remBytes
        } else {
            (chunkCount * 15) / 8
        }

        val out = ByteArray(totalBytes)
        var bitBuffer = 0
        var bitCount = 0
        var outIdx = 0

        for (i in 0 until chunkCount) {
            bitBuffer = (bitBuffer shl 15) or chunks[i]
            bitCount += 15
            while (bitCount >= 8 && outIdx < totalBytes) {
                out[outIdx++] = ((bitBuffer ushr (bitCount - 8)) and 0xFF).toByte()
                bitCount -= 8
            }
        }

        return out
    }

    override val jsConverterScript: String
        get() = SCRIPT_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToBase32768"

    override val jsDecodeFunctionName: String
        get() = "base32768ToBytes"

    companion object {
        private fun encodeChunk15(chunk15: Int): Char {
            val codePoint = when {
                chunk15 < 0x1900 -> 0x3400 + chunk15
                chunk15 < 0x6B00 -> 0x4E00 + (chunk15 - 0x1900)
                else -> 0xAC00 + (chunk15 - 0x6B00)
            }
            return codePoint.toChar()
        }

        private fun decodeChar(ch: Char): Int {
            val code = ch.code
            return when (code) {
                in 0x3400..0x4CFF -> code - 0x3400
                in 0x4E00..0x9FFF -> (code - 0x4E00) + 0x1900
                in 0xAC00..0xC0FF -> (code - 0xAC00) + 0x6B00
                else -> -1
            }
        }
    }
}
