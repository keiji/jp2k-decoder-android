package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS

// File-level lookup tables to eliminate branch prediction penalties during tight loops.
// 32768 possible 15-bit values directly mapped to their corresponding Unicode characters.
private val BASE32768_ENCODER_TABLE = CharArray(32768) { chunk15 ->
    val codePoint = when {
        chunk15 < 0x1900 -> 0x3400 + chunk15
        chunk15 < 0x6B00 -> 0x4E00 + (chunk15 - 0x1900)
        else -> 0xAC00 + (chunk15 - 0x6B00)
    }
    codePoint.toChar()
}

// Max Unicode point used in Base32768 is 0xC0FF.
// Array size 0xC100 (49408) covers all possible values in O(1) time without branching.
private val BASE32768_DECODER_TABLE = IntArray(0xC100) { -1 }.also { table ->
    for (i in 0 until 32768) {
        val charCode = BASE32768_ENCODER_TABLE[i].code
        table[charCode] = i
    }
}

private const val SCRIPT_CONVERTER = """
            (() => {
                // Pre-compute lookup tables for Base32768 to avoid String.fromCharCode and branching overhead in loops
                const ENCODER_TABLE = new Array(32768);
                const DECODER_TABLE = new Int32Array(49408).fill(-1); // Max Unicode point 0xC0FF is 49407

                for (let chunk15 = 0; chunk15 < 32768; chunk15++) {
                    let codePoint;
                    if (chunk15 < 0x1900) {
                        codePoint = 0x3400 + chunk15;
                    } else if (chunk15 < 0x6B00) {
                        codePoint = 0x4E00 + (chunk15 - 0x1900);
                    } else {
                        codePoint = 0xAC00 + (chunk15 - 0x6B00);
                    }
                    const charStr = String.fromCharCode(codePoint);
                    ENCODER_TABLE[chunk15] = charStr;
                    DECODER_TABLE[codePoint] = chunk15;
                }

                globalThis.bytesToBase32768 = function(bytes) {
                    if (!bytes || bytes.length === 0) return "";
                    
                    const len = bytes.length;
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
                            // Branchless O(1) array lookup
                            out[outIdx++] = ENCODER_TABLE[chunk15];
                        }
                    }

                    if (bitCount > 0) {
                        const remainingBits = bitBuffer & ((1 << bitCount) - 1);
                        const chunk15 = (remainingBits << (15 - bitCount)) & 0x7FFF;
                        out[outIdx++] = ENCODER_TABLE[chunk15];

                        const remBytes = len % 15;
                        if (remBytes > 0) {
                            // This happens only once at the very end, so String.fromCharCode is fine here
                            out[outIdx++] = String.fromCharCode(0x2100 + remBytes);
                        }
                    }

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

                    for (let i = 0; i < mainStrLength; i++) {
                        const code = str.charCodeAt(i);
                        if (code >= 49408) continue; // Out of bounds safety
                        
                        // Branchless O(1) array lookup
                        const val15 = DECODER_TABLE[code];
                        if (val15 >= 0) {
                            bitBuffer = (bitBuffer << 15) | val15;
                            bitCount += 15;
                            while (bitCount >= 8 && outIdx < totalBytes) {
                                out[outIdx++] = (bitBuffer >>> (bitCount - 8)) & 0xFF;
                                bitCount -= 8;
                            }
                        }
                    }

                    return outIdx === totalBytes ? out : out.subarray(0, outIdx);
                };

                globalThis.encodePayload = globalThis.bytesToBase32768;
                globalThis.decodePayload = globalThis.base32768ToBytes;
            })();
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
        // Assume you have an escapeJs() extension function handling backslashes/quotes if needed
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
            // Keep pure 32-bit arithmetic
            bitBuffer = (bitBuffer shl 8) or (data[i].toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= 15) {
                val chunk15 = (bitBuffer ushr (bitCount - 15)) and 0x7FFF
                bitCount -= 15
                // Branchless O(1) table lookup
                outChars[outIdx++] = BASE32768_ENCODER_TABLE[chunk15]
            }
        }

        if (bitCount > 0) {
            val remainingBits = bitBuffer and ((1 shl bitCount) - 1)
            val chunk15 = (remainingBits shl (15 - bitCount)) and 0x7FFF

            outChars[outIdx++] = BASE32768_ENCODER_TABLE[chunk15]

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
            // Prevent IndexOutOfBoundsException for out-of-range characters
            if (code >= 0xC100) continue

            // Branchless O(1) table lookup
            val val15 = BASE32768_DECODER_TABLE[code]
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
