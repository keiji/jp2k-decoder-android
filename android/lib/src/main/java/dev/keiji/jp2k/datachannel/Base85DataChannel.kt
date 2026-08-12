package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import java.io.ByteArrayOutputStream

private const val SCRIPT_CONVERTER = """
            globalThis.bytesToBase85 = function(bytes) {
                if (!bytes || bytes.length === 0) return "";
                const ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!#$%&()*+-;<=>?@^_`{|}~";
                const pow85 = [52200625, 614125, 7225, 85, 1];
                let result = "";
                let i = 0;
                const len = bytes.length;
                while (i < len) {
                    const remaining = len - i;
                    if (remaining >= 4) {
                        const val32 = ((bytes[i] << 24) | (bytes[i + 1] << 16) | (bytes[i + 2] << 8) | bytes[i + 3]) >>> 0;
                        let temp = val32;
                        for (let p = 0; p < 5; p++) {
                            const idx = Math.floor(temp / pow85[p]);
                            result += ALPHABET.charAt(idx);
                            temp %= pow85[p];
                        }
                        i += 4;
                    } else {
                        let val32 = 0;
                        for (let b = 0; b < remaining; b++) {
                            val32 |= (bytes[i + b] << (24 - b * 8));
                        }
                        val32 = val32 >>> 0;
                        let temp = val32;
                        for (let p = 0; p < remaining + 1; p++) {
                            const idx = Math.floor(temp / pow85[p]);
                            result += ALPHABET.charAt(idx);
                            temp %= pow85[p];
                        }
                        i += remaining;
                    }
                }
                return result;
            };

            globalThis.base85ToBytes = function(str) {
                if (!str || str.length === 0) return new Uint8Array(0);
                const ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!#$%&()*+-;<=>?@^_`{|}~";
                const lookup = new Int32Array(256).fill(-1);
                for (let i = 0; i < ALPHABET.length; i++) {
                    lookup[ALPHABET.charCodeAt(i)] = i;
                }
                const pow85 = [52200625, 614125, 7225, 85, 1];
                const out = [];
                let group = [];
                for (let i = 0; i < str.length; i++) {
                    const code = str.charCodeAt(i);
                    const val85 = lookup[code];
                    if (val85 >= 0) {
                        group.push(val85);
                        if (group.length === 5) {
                            let val32 = 0;
                            for (let p = 0; p < 5; p++) {
                                val32 += group[p] * pow85[p];
                            }
                            out.push((val32 >>> 24) & 255, (val32 >>> 16) & 255, (val32 >>> 8) & 255, val32 & 255);
                            group = [];
                        }
                    }
                }
                if (group.length > 1) {
                    const groupLen = group.length;
                    while (group.length < 5) {
                        group.push(84);
                    }
                    let val32 = 0;
                    for (let p = 0; p < 5; p++) {
                        val32 += group[p] * pow85[p];
                    }
                    const bytesToWrite = groupLen - 1;
                    for (let b = 0; b < bytesToWrite; b++) {
                        out.push((val32 >>> (24 - b * 8)) & 255);
                    }
                }
                return new Uint8Array(out);
            };

            globalThis.encodePayload = globalThis.bytesToBase85;
            globalThis.decodePayload = globalThis.base85ToBytes;
"""

/**
 * Channel that encodes binary data using RFC 1924 Base85 encoding and passes it as a JS string.
 *
 * Base85 uses the 85-character set: 0-9, a-z, A-Z, and !#$%&()*+-;<=>?@^_`{|}~
 * Does not support 'z' zero compression.
 *
 * @see JSDataChannel
 */
internal class Base85DataChannel : JSDataChannel {
    override val name: String = "Base85DataChannel"
    override val isStringMediated: Boolean = true
    override fun init(sandbox: JavaScriptSandbox) {
        // No-op — Base85 works on all devices
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        val encoded = encodePayload(wasmBytes)
        return "base85ToBytes('$encoded')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData)
        return "(async () => { globalThis.j2kData = globalThis.base85ToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        if (data.isEmpty()) return ""

        val sb = StringBuilder()
        var i = 0
        val len = data.size

        val pow85 = longArrayOf(52200625L, 614125L, 7225L, 85L, 1L)

        while (i < len) {
            val remaining = len - i
            if (remaining >= 4) {
                val b0 = data[i].toInt() and 0xFF
                val b1 = data[i + 1].toInt() and 0xFF
                val b2 = data[i + 2].toInt() and 0xFF
                val b3 = data[i + 3].toInt() and 0xFF
                val val32 = ((b0.toLong() shl 24) or (b1.toLong() shl 16) or (b2.toLong() shl 8) or b3.toLong()) and 0xFFFFFFFFL

                var temp = val32
                for (p in 0 until 5) {
                    val idx = (temp / pow85[p]).toInt()
                    sb.append(ENCODER_TABLE[idx])
                    temp %= pow85[p]
                }
                i += 4
            } else {
                // Partial block (1..3 bytes)
                var val32 = 0L
                for (b in 0 until remaining) {
                    val32 = val32 or ((data[i + b].toInt() and 0xFF).toLong() shl (24 - b * 8))
                }
                var temp = val32
                val chars = CharArray(5)
                for (p in 0 until 5) {
                    chars[p] = ENCODER_TABLE[(temp / pow85[p]).toInt()]
                    temp %= pow85[p]
                }
                // Output remaining + 1 characters
                for (p in 0 until (remaining + 1)) {
                    sb.append(chars[p])
                }
                i += remaining
            }
        }

        return sb.toString()
    }

    override fun decodePayload(encoded: String): ByteArray {
        if (encoded.isEmpty()) return ByteArray(0)

        val out = ByteArrayOutputStream()
        val pow85 = longArrayOf(52200625L, 614125L, 7225L, 85L, 1L)

        var groupCount = 0
        val group = IntArray(5)

        var idx = 0
        while (idx < encoded.length) {
            val ch = encoded[idx]
            val val85 = DECODER_TABLE[ch.code]
            if (val85 >= 0) {
                group[groupCount] = val85
                groupCount++

                if (groupCount == 5) {
                    var val32 = 0L
                    for (p in 0 until 5) {
                        val32 += group[p].toLong() * pow85[p]
                    }
                    out.write((val32 shr 24).toInt() and 0xFF)
                    out.write((val32 shr 16).toInt() and 0xFF)
                    out.write((val32 shr 8).toInt() and 0xFF)
                    out.write(val32.toInt() and 0xFF)
                    groupCount = 0
                }
            }
            idx++
        }

        if (groupCount > 1) {
            // Partial block at the end
            for (p in groupCount until 5) {
                group[p] = 84 // Padding with last character value (84)
            }
            var val32 = 0L
            for (p in 0 until 5) {
                val32 += group[p].toLong() * pow85[p]
            }
            val bytesToWrite = groupCount - 1
            for (b in 0 until bytesToWrite) {
                out.write((val32 shr (24 - b * 8)).toInt() and 0xFF)
            }
        }

        return out.toByteArray()
    }

    override val jsConverterScript: String
        get() = SCRIPT_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToBase85"

    override val jsDecodeFunctionName: String
        get() = "base85ToBytes"

    companion object {
        private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!#$%&()*+-;<=>?@^_`{|}~"
        private val ENCODER_TABLE = ALPHABET.toCharArray()
        private val DECODER_TABLE = IntArray(256) { -1 }.also { table ->
            for (i in ALPHABET.indices) {
                table[ALPHABET[i].code] = i
            }
        }
    }
}
