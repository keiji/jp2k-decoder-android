package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS

private const val SCRIPT_CONVERTER = """
            (() => {
                const pow85 = new Uint32Array([52200625, 614125, 7225, 85, 1]);

                globalThis.bytesToAscii85 = function(bytes) {
                    if (!bytes || bytes.length === 0) return "";
                    let result = "";
                    let i = 0;
                    const len = bytes.length;
                    while (i < len) {
                        const remaining = len - i;
                        if (remaining >= 4) {
                            const val32 = ((bytes[i] << 24) | (bytes[i + 1] << 16) | (bytes[i + 2] << 8) | bytes[i + 3]) >>> 0;
                            if (val32 === 0) {
                                result += "z";
                            } else {
                                let temp = val32;
                                for (let p = 0; p < 5; p++) {
                                    const c = Math.floor(temp / pow85[p]) + 33;
                                    result += String.fromCharCode(c);
                                    temp %= pow85[p];
                                }
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
                                const c = Math.floor(temp / pow85[p]) + 33;
                                result += String.fromCharCode(c);
                                temp %= pow85[p];
                            }
                            i += remaining;
                        }
                    }
                    return result;
                };

                globalThis.ascii85ToBytes = function(str) {
                    if (!str || str.length === 0) return new Uint8Array(0);
                    const out = new Uint8Array(str.length * 4); // 'z' expands to 4 bytes
                    let outIdx = 0;
                    const group = new Uint8Array(5);
                    let groupCount = 0;
                    for (let i = 0; i < str.length; i++) {
                        const ch = str.charAt(i);
                        const code = str.charCodeAt(i);
                        if (ch === 'z' && groupCount === 0) {
                            out[outIdx++] = 0;
                            out[outIdx++] = 0;
                            out[outIdx++] = 0;
                            out[outIdx++] = 0;
                        } else if (code >= 33 && code <= 117) {
                            group[groupCount++] = code - 33;
                            if (groupCount === 5) {
                                let val32 = 0;
                                for (let p = 0; p < 5; p++) {
                                    val32 += group[p] * pow85[p];
                                }
                                out[outIdx++] = (val32 >>> 24) & 255;
                                out[outIdx++] = (val32 >>> 16) & 255;
                                out[outIdx++] = (val32 >>> 8) & 255;
                                out[outIdx++] = val32 & 255;
                                groupCount = 0;
                            }
                        }
                    }
                    if (groupCount > 1) {
                        const groupLen = groupCount;
                        while (groupCount < 5) {
                            group[groupCount++] = 84;
                        }
                        let val32 = 0;
                        for (let p = 0; p < 5; p++) {
                            val32 += group[p] * pow85[p];
                        }
                        const bytesToWrite = groupLen - 1;
                        for (let b = 0; b < bytesToWrite; b++) {
                            out[outIdx++] = (val32 >>> (24 - b * 8)) & 255;
                        }
                    }
                    return out.slice(0, outIdx);
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
    override fun init(sandbox: JavaScriptSandbox) {
        // No-op — Ascii85 works on all devices
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        val encoded = encodePayload(wasmBytes)
        return "ascii85ToBytes('$encoded')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData)
        return "(async () => { globalThis.j2kData = globalThis.ascii85ToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        if (data.isEmpty()) return ""

        val sb = StringBuilder((data.size * 5) / 4 + 5)
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

                if (val32 == 0L) {
                    sb.append('z')
                } else {
                    var temp = val32
                    for (p in 0 until 5) {
                        val c = ((temp / pow85[p]) + 33).toInt().toChar()
                        sb.append(c)
                        temp %= pow85[p]
                    }
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
                    chars[p] = ((temp / pow85[p]) + 33).toInt().toChar()
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

        val out = ByteArray(encoded.length * 4) // 'z' expands 1 char to 4 bytes
        var outIdx = 0
        val pow85 = longArrayOf(52200625L, 614125L, 7225L, 85L, 1L)

        var groupCount = 0
        val group = IntArray(5)

        var idx = 0
        while (idx < encoded.length) {
            val ch = encoded[idx]
            if (ch == 'z' && groupCount == 0) {
                out[outIdx++] = 0
                out[outIdx++] = 0
                out[outIdx++] = 0
                out[outIdx++] = 0
            } else if (ch in '!'..'u') {
                group[groupCount] = ch.code - 33
                groupCount++

                if (groupCount == 5) {
                    var val32 = 0L
                    for (p in 0 until 5) {
                        val32 += group[p].toLong() * pow85[p]
                    }
                    out[outIdx++] = ((val32 shr 24).toInt() and 0xFF).toByte()
                    out[outIdx++] = ((val32 shr 16).toInt() and 0xFF).toByte()
                    out[outIdx++] = ((val32 shr 8).toInt() and 0xFF).toByte()
                    out[outIdx++] = (val32.toInt() and 0xFF).toByte()
                    groupCount = 0
                }
            }
            idx++
        }

        if (groupCount > 1) {
            // Partial block at the end
            for (p in groupCount until 5) {
                group[p] = 84 // 'u' - 33 = 84
            }
            var val32 = 0L
            for (p in 0 until 5) {
                val32 += group[p].toLong() * pow85[p]
            }
            val bytesToWrite = groupCount - 1
            for (b in 0 until bytesToWrite) {
                out[outIdx++] = ((val32 shr (24 - b * 8)).toInt() and 0xFF).toByte()
            }
        }

        return out.copyOf(outIdx)
    }

    override val jsConverterScript: String
        get() = SCRIPT_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToAscii85"

    override val jsDecodeFunctionName: String
        get() = "ascii85ToBytes"
}
