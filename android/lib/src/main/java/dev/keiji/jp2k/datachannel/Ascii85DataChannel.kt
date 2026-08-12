package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS

private const val SCRIPT_CONVERTER = """
            (() => {
                globalThis.bytesToAscii85 = function(bytes) {
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
                            if (val32 === 0) {
                                out[outIdx++] = "z";
                            } else {
                                // Loop unrolling for performance and avoiding intermediate arrays
                                let temp = val32;
                                out[outIdx++] = String.fromCharCode(Math.floor(temp / 52200625) + 33);
                                temp %= 52200625;
                                out[outIdx++] = String.fromCharCode(Math.floor(temp / 614125) + 33);
                                temp %= 614125;
                                out[outIdx++] = String.fromCharCode(Math.floor(temp / 7225) + 33);
                                temp %= 7225;
                                out[outIdx++] = String.fromCharCode(Math.floor(temp / 85) + 33);
                                temp %= 85;
                                out[outIdx++] = String.fromCharCode(temp + 33);
                            }
                            i += 4;
                        } else {
                            let val32 = 0;
                            for (let b = 0; b < remaining; b++) {
                                val32 |= (bytes[i + b] << (24 - b * 8));
                            }
                            val32 >>>= 0;
                            let temp = val32;
                            
                            const c0 = String.fromCharCode(Math.floor(temp / 52200625) + 33); temp %= 52200625;
                            const c1 = String.fromCharCode(Math.floor(temp / 614125) + 33); temp %= 614125;
                            const c2 = String.fromCharCode(Math.floor(temp / 7225) + 33); temp %= 7225;
                            const c3 = String.fromCharCode(Math.floor(temp / 85) + 33); temp %= 85;
                            const c4 = String.fromCharCode(temp + 33);
                            
                            out[outIdx++] = c0;
                            if (remaining >= 1) out[outIdx++] = c1;
                            if (remaining >= 2) out[outIdx++] = c2;
                            if (remaining >= 3) out[outIdx++] = c3;
                            
                            i += remaining;
                        }
                    }
                    // Join array into a string at once for better performance with large payloads
                    return out.join('');
                };

                globalThis.ascii85ToBytes = function(str) {
                    if (!str || str.length === 0) return new Uint8Array(0);
                    
                    const len = str.length;
                    const out = new Uint8Array(len * 4); // 'z' expands 1 char to 4 bytes
                    let outIdx = 0;
                    
                    let groupCount = 0;
                    // Use discrete variables instead of an array to avoid allocation/lookup overhead in loops
                    let g0 = 0, g1 = 0, g2 = 0, g3 = 0, g4 = 0;

                    for (let i = 0; i < len; i++) {
                        const code = str.charCodeAt(i);
                        if (code === 122 && groupCount === 0) { // 'z'
                            out[outIdx++] = 0;
                            out[outIdx++] = 0;
                            out[outIdx++] = 0;
                            out[outIdx++] = 0;
                        } else if (code >= 33 && code <= 117) { // '!' to 'u'
                            const val = code - 33;
                            if (groupCount === 0) g0 = val;
                            else if (groupCount === 1) g1 = val;
                            else if (groupCount === 2) g2 = val;
                            else if (groupCount === 3) g3 = val;
                            else if (groupCount === 4) {
                                g4 = val;
                                const val32 = g0 * 52200625 + g1 * 614125 + g2 * 7225 + g3 * 85 + g4;
                                out[outIdx++] = (val32 >>> 24) & 255;
                                out[outIdx++] = (val32 >>> 16) & 255;
                                out[outIdx++] = (val32 >>> 8) & 255;
                                out[outIdx++] = val32 & 255;
                                groupCount = -1; // Reset to 0 below
                            }
                            groupCount++;
                        }
                    }
                    
                    if (groupCount > 1) {
                        // Pad missing characters with 'u' (84)
                        if (groupCount <= 2) g2 = 84;
                        if (groupCount <= 3) g3 = 84;
                        if (groupCount <= 4) g4 = 84;
                        
                        const val32 = g0 * 52200625 + g1 * 614125 + g2 * 7225 + g3 * 85 + g4;
                        const bytesToWrite = groupCount - 1;
                        for (let b = 0; b < bytesToWrite; b++) {
                            out[outIdx++] = (val32 >>> (24 - b * 8)) & 255;
                        }
                    }
                    
                    return outIdx === out.length ? out : out.subarray(0, outIdx);
                };

                globalThis.encodePayload = globalThis.bytesToAscii85;
                globalThis.decodePayload = globalThis.ascii85ToBytes;
            })();
"""

/**
 * Channel that encodes binary data using Adobe Ascii85 (Base85) encoding and passes it as a JS string.
 *
 * Ascii85 uses the ASCII characters '!' (33) to 'u' (117) and supports 'z' zero compression.
 *
 * @see JSDataChannel
 */
internal class Ascii85DataChannel : JSDataChannel {
    override val name: String = "Ascii85DataChannel"
    override val isStringMediated: Boolean = true
    override fun init(sandbox: JavaScriptSandbox) {
        // No-op — Ascii85 works on all devices
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        val encoded = encodePayload(wasmBytes).escapeJs()
        return "ascii85ToBytes('$encoded')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData).escapeJs()
        return "(async () => { globalThis.j2kData = globalThis.ascii85ToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        val len = data.size
        if (len == 0) return ""

        // Calculate maximum potential characters
        val maxChars = (len * 5) / 4 + 5
        val out = CharArray(maxChars)
        var outIdx = 0
        var i = 0

        while (i < len) {
            val remaining = len - i
            if (remaining >= 4) {
                val val32 = (((data[i].toLong() and 0xFF) shl 24) or
                        ((data[i + 1].toLong() and 0xFF) shl 16) or
                        ((data[i + 2].toLong() and 0xFF) shl 8) or
                        (data[i + 3].toLong() and 0xFF))

                if (val32 == 0L) {
                    out[outIdx++] = 'z'
                } else {
                    // Loop unrolling for better performance
                    var temp = val32
                    out[outIdx++] = ((temp / 52200625L) + 33).toInt().toChar()
                    temp %= 52200625L
                    out[outIdx++] = ((temp / 614125L) + 33).toInt().toChar()
                    temp %= 614125L
                    out[outIdx++] = ((temp / 7225L) + 33).toInt().toChar()
                    temp %= 7225L
                    out[outIdx++] = ((temp / 85L) + 33).toInt().toChar()
                    temp %= 85L
                    out[outIdx++] = (temp + 33).toInt().toChar()
                }
                i += 4
            } else {
                // Partial block (1..3 bytes)
                var val32 = 0L
                for (b in 0 until remaining) {
                    val32 = val32 or ((data[i + b].toLong() and 0xFF) shl (24 - b * 8))
                }

                var temp = val32
                val c0 = ((temp / 52200625L) + 33).toInt().toChar(); temp %= 52200625L
                val c1 = ((temp / 614125L) + 33).toInt().toChar(); temp %= 614125L
                val c2 = ((temp / 7225L) + 33).toInt().toChar(); temp %= 7225L
                val c3 = ((temp / 85L) + 33).toInt().toChar(); temp %= 85L
                val c4 = (temp + 33).toInt().toChar()

                out[outIdx++] = c0
                if (remaining >= 1) out[outIdx++] = c1
                if (remaining >= 2) out[outIdx++] = c2
                if (remaining >= 3) out[outIdx++] = c3

                i += remaining
            }
        }

        return String(out, 0, outIdx)
    }

    override fun decodePayload(encoded: String): ByteArray {
        val len = encoded.length
        if (len == 0) return ByteArray(0)

        val out = ByteArray(len * 4) // 'z' expands 1 char to 4 bytes
        var outIdx = 0

        var groupCount = 0
        // Use discrete variables instead of IntArray to avoid overhead
        var g0 = 0L; var g1 = 0L; var g2 = 0L; var g3 = 0L; var g4 = 0L

        for (idx in 0 until len) {
            val ch = encoded[idx]
            if (ch == 'z' && groupCount == 0) {
                out[outIdx++] = 0
                out[outIdx++] = 0
                out[outIdx++] = 0
                out[outIdx++] = 0
            } else if (ch in '!'..'u') {
                val v = (ch.code - 33).toLong()
                when (groupCount) {
                    0 -> g0 = v
                    1 -> g1 = v
                    2 -> g2 = v
                    3 -> g3 = v
                    4 -> {
                        g4 = v
                        val val32 = g0 * 52200625L + g1 * 614125L + g2 * 7225L + g3 * 85L + g4
                        out[outIdx++] = ((val32 ushr 24) and 0xFF).toByte()
                        out[outIdx++] = ((val32 ushr 16) and 0xFF).toByte()
                        out[outIdx++] = ((val32 ushr 8) and 0xFF).toByte()
                        out[outIdx++] = (val32 and 0xFF).toByte()
                        groupCount = -1 // Reset for next iteration
                    }
                }
                groupCount++
            }
        }

        if (groupCount > 1) {
            // Partial block at the end (pad with 'u' -> 84)
            if (groupCount <= 2) g2 = 84L
            if (groupCount <= 3) g3 = 84L
            if (groupCount <= 4) g4 = 84L

            val val32 = g0 * 52200625L + g1 * 614125L + g2 * 7225L + g3 * 85L + g4
            val bytesToWrite = groupCount - 1
            for (b in 0 until bytesToWrite) {
                out[outIdx++] = ((val32 ushr (24 - b * 8)) and 0xFF).toByte()
            }
        }

        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    override val jsConverterScript: String
        get() = SCRIPT_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToAscii85"

    override val jsDecodeFunctionName: String
        get() = "ascii85ToBytes"
}
