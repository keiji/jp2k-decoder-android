package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import java.util.Base64

private const val SCRIPT_CONVERTER = """
            (() => {
                // Initialize lookup tables once to avoid overhead during tight loops
                // Note the URL-safe alphabet ends with '-' and '_'
                const ALPHABET_STR = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
                const ENCODER_TABLE = new Array(64);
                const DECODER_TABLE = new Int32Array(256).fill(-1);
                
                for (let i = 0; i < 64; i++) {
                    ENCODER_TABLE[i] = ALPHABET_STR[i];
                    DECODER_TABLE[ALPHABET_STR.charCodeAt(i)] = i;
                }

                globalThis.bytesToBase64Url = function(bytes) {
                    if (!bytes || bytes.length === 0) return "";
                    
                    const len = bytes.length;
                    const mainLen = len - (len % 3);
                    
                    // Pre-allocate array for exactly the required number of characters
                    const outLen = Math.ceil(len / 3) * 4;
                    const out = new Array(outLen);
                    let outIdx = 0;

                    // Process full 3-byte chunks without any branching (if statements)
                    for (let i = 0; i < mainLen; i += 3) {
                        const chunk = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
                        out[outIdx++] = ENCODER_TABLE[(chunk >> 18) & 63];
                        out[outIdx++] = ENCODER_TABLE[(chunk >> 12) & 63];
                        out[outIdx++] = ENCODER_TABLE[(chunk >> 6) & 63];
                        out[outIdx++] = ENCODER_TABLE[chunk & 63];
                    }

                    // Handle remaining bytes (1 or 2) and padding outside the loop
                    const rem = len % 3;
                    if (rem === 1) {
                        const chunk = bytes[mainLen] << 16;
                        out[outIdx++] = ENCODER_TABLE[(chunk >> 18) & 63];
                        out[outIdx++] = ENCODER_TABLE[(chunk >> 12) & 63];
                        out[outIdx++] = "=";
                        out[outIdx++] = "=";
                    } else if (rem === 2) {
                        const chunk = (bytes[mainLen] << 16) | (bytes[mainLen + 1] << 8);
                        out[outIdx++] = ENCODER_TABLE[(chunk >> 18) & 63];
                        out[outIdx++] = ENCODER_TABLE[(chunk >> 12) & 63];
                        out[outIdx++] = ENCODER_TABLE[(chunk >> 6) & 63];
                        out[outIdx++] = "=";
                    }

                    return out.join('');
                };

                globalThis.base64UrlToBytes = function(str) {
                    if (!str || str.length === 0) return new Uint8Array(0);

                    const len = str.length;
                    let pad = 0;
                    if (str[len - 1] === "=") pad++;
                    if (str[len - 2] === "=") pad++;

                    // Pre-calculate exact byte array size
                    const outLen = (len * 3 / 4) - pad;
                    const out = new Uint8Array(outLen);
                    let outIdx = 0;

                    // Separate the main block from the padded block to avoid branch checks
                    const mainLen = pad > 0 ? len - 4 : len;

                    // Process full 4-character chunks without branching
                    for (let i = 0; i < mainLen; i += 4) {
                        const chunk = (DECODER_TABLE[str.charCodeAt(i)] << 18) |
                                      (DECODER_TABLE[str.charCodeAt(i + 1)] << 12) |
                                      (DECODER_TABLE[str.charCodeAt(i + 2)] << 6) |
                                       DECODER_TABLE[str.charCodeAt(i + 3)];
                        
                        out[outIdx++] = (chunk >> 16) & 255;
                        out[outIdx++] = (chunk >> 8) & 255;
                        out[outIdx++] = chunk & 255;
                    }

                    // Handle the last padded chunk if necessary
                    if (pad > 0) {
                        const c1 = DECODER_TABLE[str.charCodeAt(mainLen)];
                        const c2 = DECODER_TABLE[str.charCodeAt(mainLen + 1)];
                        const c3 = pad === 1 ? DECODER_TABLE[str.charCodeAt(mainLen + 2)] : 0;
                        
                        const chunk = (c1 << 18) | (c2 << 12) | (c3 << 6);
                        
                        out[outIdx++] = (chunk >> 16) & 255;
                        if (pad === 1) {
                            out[outIdx++] = (chunk >> 8) & 255;
                        }
                    }

                    return out;
                };

                globalThis.encodePayload = globalThis.bytesToBase64Url;
                globalThis.decodePayload = globalThis.base64UrlToBytes;
            })();
"""

/**
 * Channel that Base64Url-encodes binary data and passes it as a JS string.
 *
 * @see JSDataChannel
 */
internal class Base64UrlDataChannel : JSDataChannel {
    override val name: String = "Base64UrlDataChannel"
    override val isStringMediated: Boolean = true
    override fun init(sandbox: JavaScriptSandbox) {
        // No-op — Base64Url works on all devices
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        val encoded = encodePayload(wasmBytes).escapeJs()
        return "base64UrlToBytes('$encoded')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData).escapeJs()
        return "(async () => { globalThis.j2kData = globalThis.base64UrlToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        return Base64.getUrlEncoder().encodeToString(data)
    }

    override fun decodePayload(encoded: String): ByteArray {
        return Base64.getUrlDecoder().decode(encoded)
    }

    override val jsConverterScript: String
        get() = SCRIPT_CONVERTER.minifyJs()

    override val jsEncodeFunctionName: String
        get() = "bytesToBase64Url"

    override val jsDecodeFunctionName: String
        get() = "base64UrlToBytes"
}
