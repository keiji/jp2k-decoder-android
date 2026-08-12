package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS

private const val SCRIPT_CONVERTER = """
            globalThis.bytesToBase32768 = function(bytes) {
                if (!bytes || bytes.length === 0) return "";
                
                const len = bytes.length;
                // Pre-calculate the maximum required characters and allocate the array
                const maxChars = Math.floor((len * 8 + 14) / 15) + 1;
                const out = new Array(maxChars);
                let outIdx = 0;
                
                let bitBuffer = 0;
                let bitCount = 0;

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
                        out[outIdx++] = String.fromCharCode(codePoint);
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
                    out[outIdx++] = String.fromCharCode(codePoint);

                    const remBytes = len % 15;
                    if (remBytes > 0) {
                        out[outIdx++] = String.fromCharCode(0x2100 + remBytes);
                    }
                }

                // Join array into a string at once for better performance with large payloads
                return out.join('');
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

                let totalBytes;
                if (remBytes > 0) {
                    const fullBlocks = Math.floor(mainStrLength / 8);
                    totalBytes = fullBlocks * 15 + remBytes;
                } else {
                    totalBytes = Math.floor((mainStrLength * 15) / 8);
                }

                const out = new Uint8Array(totalBytes);
                let bitBuffer = 0;
                let bitCount = 0;
                let outIdx = 0;

                // Process in a single pass without intermediate arrays
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
                        bitBuffer = (bitBuffer << 15) | val15;
                        bitCount += 15;
                        while (bitCount >= 8 && outIdx < totalBytes) {
                            out[outIdx++] = (bitBuffer >>> (bitCount - 8)) & 0xFF;
                            bitCount -= 8;
                        }
                    }
                }

                // Fallback to subarray if invalid characters were skipped
                return outIdx === totalBytes ? out : out.subarray(0, outIdx);
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
    override val isStringMediated: Boolean = true
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
        val len = data.size
        if (len == 0) return ""

        // Calculate maximum required characters (1 character per 15 bits, plus potential padding)
        val maxChars = (len * 8 + 14) / 15 + 1
        val outChars = CharArray(maxChars)
        var outIdx = 0

        var bitBuffer = 0
        var bitCount = 0

        for (i in 0 until len) {
            bitBuffer = (bitBuffer shl 8) or (data[i].toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= 15) {
                val chunk15 = (bitBuffer ushr (bitCount - 15)) and 0x7FFF
                bitCount -= 15

                // Inline encoding logic
                val codePoint = when {
                    chunk15 < 0x1900 -> 0x3400 + chunk15
                    chunk15 < 0x6B00 -> 0x4E00 + (chunk15 - 0x1900)
                    else -> 0xAC00 + (chunk15 - 0x6B00)
                }
                outChars[outIdx++] = codePoint.toChar()
            }
        }

        if (bitCount > 0) {
            val remainingBits = bitBuffer and ((1 shl bitCount) - 1)
            val chunk15 = (remainingBits shl (15 - bitCount)) and 0x7FFF

            val codePoint = when {
                chunk15 < 0x1900 -> 0x3400 + chunk15
                chunk15 < 0x6B00 -> 0x4E00 + (chunk15 - 0x1900)
                else -> 0xAC00 + (chunk15 - 0x6B00)
            }
            outChars[outIdx++] = codePoint.toChar()

            val remBytes = len % 15
            if (remBytes > 0) {
                outChars[outIdx++] = (0x2100 + remBytes).toChar()
            }
        }

        return String(outChars, 0, outIdx)
    }

    override fun decodePayload(encoded: String): ByteArray {
        val len = encoded.length
        if (len == 0) return ByteArray(0)

        var remBytes = 0
        var dataLen = len

        val lastCode = encoded[len - 1].code
        if (lastCode in 0x2101..0x210E) {
            remBytes = lastCode - 0x2100
            dataLen = len - 1
        }

        // Calculate maximum potential total bytes
        val totalBytes = if (remBytes > 0) {
            val fullBlocks = dataLen / 8
            fullBlocks * 15 + remBytes
        } else {
            (dataLen * 15) / 8
        }

        val out = ByteArray(totalBytes)
        var bitBuffer = 0
        var bitCount = 0
        var outIdx = 0

        for (i in 0 until dataLen) {
            val code = encoded[i].code
            // Inline decoding logic
            val val15 = when (code) {
                in 0x3400..0x4CFF -> code - 0x3400
                in 0x4E00..0x9FFF -> (code - 0x4E00) + 0x1900
                in 0xAC00..0xC0FF -> (code - 0xAC00) + 0x6B00
                else -> -1
            }

            if (val15 >= 0) {
                bitBuffer = (bitBuffer shl 15) or val15
                bitCount += 15
                while (bitCount >= 8 && outIdx < totalBytes) {
                    out[outIdx++] = ((bitBuffer ushr (bitCount - 8)) and 0xFF).toByte()
                    bitCount -= 8
                }
            }
        }

        // Return exact array, fallback to copyOf if invalid characters were skipped
        return if (outIdx == totalBytes) out else out.copyOf(outIdx)
    }

    override val jsConverterScript: String
        get() = SCRIPT_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToBase32768"

    override val jsDecodeFunctionName: String
        get() = "base32768ToBytes"
}
