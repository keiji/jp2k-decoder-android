package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS

// Move lookup tables to the file (top) level to avoid the access overhead
// associated with companion object getter methods during tight loops.
private const val BASE85_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!#$%&()*+-;<=>?@^_`{|}~"

private val BASE85_ENCODER_TABLE = BASE85_ALPHABET.toCharArray()

private val BASE85_DECODER_TABLE = IntArray(256) { -1 }.also { table ->
    for (i in BASE85_ALPHABET.indices) {
        table[BASE85_ALPHABET[i].code] = i
    }
}

private const val SCRIPT_CONVERTER = """
            (() => {
                const ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!#$%&()*+-;<=>?@^_`{|}~";
                const lookup = new Int32Array(256).fill(-1);
                for (let i = 0; i < ALPHABET.length; i++) {
                    lookup[ALPHABET.charCodeAt(i)] = i;
                }

                globalThis.bytesToBase85 = function(bytes) {
                    if (!bytes || bytes.length === 0) return "";
                    
                    const len = bytes.length;
                    // Pre-calculate max characters: (len * 5 / 4) + 5
                    const maxChars = Math.floor(len * 5 / 4) + 5;
                    const out = new Array(maxChars);
                    let outIdx = 0;
                    let i = 0;

                    while (i < len) {
                        const remaining = len - i;
                        if (remaining >= 4) {
                            const val32 = ((bytes[i] << 24) | (bytes[i + 1] << 16) | (bytes[i + 2] << 8) | bytes[i + 3]) >>> 0;
                            let temp = val32;
                            
                            // Loop unrolling for better performance and avoiding intermediate arrays
                            out[outIdx++] = ALPHABET[Math.floor(temp / 52200625)]; temp %= 52200625;
                            out[outIdx++] = ALPHABET[Math.floor(temp / 614125)]; temp %= 614125;
                            out[outIdx++] = ALPHABET[Math.floor(temp / 7225)]; temp %= 7225;
                            out[outIdx++] = ALPHABET[Math.floor(temp / 85)]; temp %= 85;
                            out[outIdx++] = ALPHABET[temp];
                            
                            i += 4;
                        } else {
                            let val32 = 0;
                            for (let b = 0; b < remaining; b++) {
                                val32 |= (bytes[i + b] << (24 - b * 8));
                            }
                            val32 >>>= 0;
                            let temp = val32;
                            
                            const c0 = ALPHABET[Math.floor(temp / 52200625)]; temp %= 52200625;
                            const c1 = ALPHABET[Math.floor(temp / 614125)]; temp %= 614125;
                            const c2 = ALPHABET[Math.floor(temp / 7225)]; temp %= 7225;
                            const c3 = ALPHABET[Math.floor(temp / 85)]; temp %= 85;
                            const c4 = ALPHABET[temp];
                            
                            out[outIdx++] = c0;
                            if (remaining >= 1) out[outIdx++] = c1;
                            if (remaining >= 2) out[outIdx++] = c2;
                            if (remaining >= 3) out[outIdx++] = c3;
                            
                            i += remaining;
                        }
                    }
                    
                    // Join array into a string at once for large payloads
                    return out.join('');
                };

                globalThis.base85ToBytes = function(str) {
                    if (!str || str.length === 0) return new Uint8Array(0);
                    
                    const len = str.length;
                    const out = new Uint8Array(len); // Length is an upper bound (4 bytes for every 5 chars)
                    let outIdx = 0;
                    
                    let groupCount = 0;
                    // Use discrete variables instead of an array to avoid allocation overhead
                    let g0 = 0, g1 = 0, g2 = 0, g3 = 0, g4 = 0;

                    for (let i = 0; i < len; i++) {
                        const code = str.charCodeAt(i);
                        // Prevent out-of-bounds lookup if code > 255
                        if (code >= 256) continue;
                        
                        const val85 = lookup[code];
                        if (val85 >= 0) {
                            if (groupCount === 0) g0 = val85;
                            else if (groupCount === 1) g1 = val85;
                            else if (groupCount === 2) g2 = val85;
                            else if (groupCount === 3) g3 = val85;
                            else if (groupCount === 4) {
                                g4 = val85;
                                const val32 = g0 * 52200625 + g1 * 614125 + g2 * 7225 + g3 * 85 + g4;
                                out[outIdx++] = (val32 >>> 24) & 255;
                                out[outIdx++] = (val32 >>> 16) & 255;
                                out[outIdx++] = (val32 >>> 8) & 255;
                                out[outIdx++] = val32 & 255;
                                groupCount = -1; // Reset for next iteration
                            }
                            groupCount++;
                        }
                    }
                    
                    if (groupCount > 1) {
                        // Pad missing characters with 84 (padding with the last character value)
                        if (groupCount <= 2) g2 = 84;
                        if (groupCount <= 3) g3 = 84;
                        if (groupCount <= 4) g4 = 84;
                        
                        const val32 = g0 * 52200625 + g1 * 614125 + g2 * 7225 + g3 * 85 + g4;
                        const bytesToWrite = groupCount - 1;
                        for (let b = 0; b < bytesToWrite; b++) {
                            out[outIdx++] = (val32 >>> (24 - b * 8)) & 255;
                        }
                    }
                    
                    return outIdx === len ? out : out.subarray(0, outIdx);
                };

                globalThis.encodePayload = globalThis.bytesToBase85;
                globalThis.decodePayload = globalThis.base85ToBytes;
            })();
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
    override fun init(sandbox: JavaScriptSandbox) {}

    override fun getWasmExpression(isolate: JavaScriptIsolate, wasmBytes: ByteArray): String {
        val encoded = encodePayload(wasmBytes)
        return "base85ToBytes('$encoded')"
    }

    override fun getJ2KExpression(isolate: JavaScriptIsolate, j2kData: ByteArray): String {
        val encoded = encodePayload(j2kData)
        return "(async () => { globalThis.j2kData = globalThis.base85ToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        val len = data.size
        if (len == 0) return ""

        val maxChars = (len * 5) / 4 + 5
        val out = CharArray(maxChars)
        var outIdx = 0
        var i = 0

        while (i < len) {
            val remaining = len - i
            if (remaining >= 4) {
                val val32 = ((data[i].toInt() and 0xFF) shl 24) or
                        ((data[i + 1].toInt() and 0xFF) shl 16) or
                        ((data[i + 2].toInt() and 0xFF) shl 8) or
                        (data[i + 3].toInt() and 0xFF)

                var temp = val32.toUInt()
                val q0 = temp / 52200625u; out[outIdx++] = BASE85_ENCODER_TABLE[q0.toInt()]; temp -= q0 * 52200625u
                val q1 = temp / 614125u;   out[outIdx++] = BASE85_ENCODER_TABLE[q1.toInt()]; temp -= q1 * 614125u
                val q2 = temp / 7225u;     out[outIdx++] = BASE85_ENCODER_TABLE[q2.toInt()]; temp -= q2 * 7225u
                val q3 = temp / 85u;       out[outIdx++] = BASE85_ENCODER_TABLE[q3.toInt()]; temp -= q3 * 85u
                out[outIdx++] = BASE85_ENCODER_TABLE[temp.toInt()]

                i += 4
            } else {
                var val32 = 0
                for (b in 0 until remaining) {
                    val32 = val32 or ((data[i + b].toInt() and 0xFF) shl (24 - b * 8))
                }

                var temp = val32.toUInt()
                val q0 = temp / 52200625u; temp -= q0 * 52200625u
                val q1 = temp / 614125u;   temp -= q1 * 614125u
                val q2 = temp / 7225u;     temp -= q2 * 7225u
                val q3 = temp / 85u;       temp -= q3 * 85u

                out[outIdx++] = BASE85_ENCODER_TABLE[q0.toInt()]
                if (remaining >= 1) out[outIdx++] = BASE85_ENCODER_TABLE[q1.toInt()]
                if (remaining >= 2) out[outIdx++] = BASE85_ENCODER_TABLE[q2.toInt()]
                if (remaining >= 3) out[outIdx++] = BASE85_ENCODER_TABLE[q3.toInt()]

                i += remaining
            }
        }

        return String(out, 0, outIdx)
    }

    override fun decodePayload(encoded: String): ByteArray {
        val len = encoded.length
        if (len == 0) return ByteArray(0)

        val out = ByteArray(len)
        var outIdx = 0

        val group = IntArray(5)
        var groupCount = 0

        for (idx in 0 until len) {
            val code = encoded[idx].code
            if (code >= 256) continue

            val val85 = BASE85_DECODER_TABLE[code]
            if (val85 >= 0) {
                group[groupCount++] = val85

                if (groupCount == 5) {
                    val val32 = group[0] * 52200625 +
                            group[1] * 614125 +
                            group[2] * 7225 +
                            group[3] * 85 +
                            group[4]

                    out[outIdx++] = ((val32 ushr 24) and 0xFF).toByte()
                    out[outIdx++] = ((val32 ushr 16) and 0xFF).toByte()
                    out[outIdx++] = ((val32 ushr 8) and 0xFF).toByte()
                    out[outIdx++] = (val32 and 0xFF).toByte()
                    groupCount = 0
                }
            }
        }

        if (groupCount > 1) {
            for (p in groupCount until 5) {
                group[p] = 84
            }
            val val32 = group[0] * 52200625 +
                    group[1] * 614125 +
                    group[2] * 7225 +
                    group[3] * 85 +
                    group[4]

            val bytesToWrite = groupCount - 1
            for (b in 0 until bytesToWrite) {
                out[outIdx++] = ((val32 ushr (24 - b * 8)) and 0xFF).toByte()
            }
        }

        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    override val jsConverterScript: String
        get() = SCRIPT_CONVERTER.minifyJs()

    override val jsEncodeFunctionName: String
        get() = "bytesToBase85"

    override val jsDecodeFunctionName: String
        get() = "base85ToBytes"
}
