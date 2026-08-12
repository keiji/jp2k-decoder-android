package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import java.util.Base64

private const val SCRIPT_CONVERTER = """
            globalThis.bytesToBase64 = function(bytes) {
                const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                let output = "";
                for (let i = 0; i < bytes.length; i += 3) {
                    const b1 = bytes[i];
                    const b2 = i + 1 < bytes.length ? bytes[i + 1] : 0;
                    const b3 = i + 2 < bytes.length ? bytes[i + 2] : 0;

                    const e1 = b1 >> 2;
                    const e2 = ((b1 & 3) << 4) | (b2 >> 4);
                    const e3 = ((b2 & 15) << 2) | (b3 >> 6);
                    const e4 = b3 & 63;

                    output += chars.charAt(e1) + chars.charAt(e2);
                    if (i + 1 < bytes.length) {
                        output += chars.charAt(e3);
                    } else {
                        output += "=";
                    }
                    if (i + 2 < bytes.length) {
                        output += chars.charAt(e4);
                    } else {
                        output += "=";
                    }
                }
                return output;
            };

            globalThis.base64ToBytes = function(base64) {
                const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                const lookup = new Uint8Array(256);
                for (let i = 0; i < chars.length; i++) {
                    lookup[chars.charCodeAt(i)] = i;
                }

                let bufferLength = base64.length * 0.75;
                let len = base64.length;
                let i, p = 0, encoded1, encoded2, encoded3, encoded4;

                if (base64[base64.length - 1] === "=") {
                    bufferLength--;
                    if (base64[base64.length - 2] === "=") {
                        bufferLength--;
                    }
                }

                const bytes = new Uint8Array(bufferLength);

                for (i = 0; i < len; i += 4) {
                    encoded1 = lookup[base64.charCodeAt(i)];
                    encoded2 = lookup[base64.charCodeAt(i + 1)];
                    encoded3 = lookup[base64.charCodeAt(i + 2)];
                    encoded4 = lookup[base64.charCodeAt(i + 3)];

                    bytes[p++] = (encoded1 << 2) | (encoded2 >> 4);
                    if (p < bufferLength) bytes[p++] = ((encoded2 & 15) << 4) | (encoded3 >> 2);
                    if (p < bufferLength) bytes[p++] = ((encoded3 & 3) << 6) | (encoded4 & 63);
                }

                return bytes;
            };

            globalThis.encodePayload = globalThis.bytesToBase64;
            globalThis.decodePayload = globalThis.base64ToBytes;
"""

/**
 * Channel that Base64-encodes binary data and passes it as a JS string.
 *
 * @see JSDataChannel
 */
internal class Base64DataChannel : JSDataChannel {
    override val name: String = "Base64DataChannel"
    override val isStringMediated: Boolean = true
    override fun init(sandbox: JavaScriptSandbox) {
        // No-op — Base64 works on all devices
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        val encoded = encodePayload(wasmBytes)
        return "base64ToBytes('$encoded')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData)
        return "(async () => { globalThis.j2kData = globalThis.base64ToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        return Base64.getEncoder().encodeToString(data)
    }

    override fun decodePayload(encoded: String): ByteArray {
        return Base64.getDecoder().decode(encoded)
    }

    override val jsConverterScript: String
        get() = SCRIPT_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToBase64"

    override val jsDecodeFunctionName: String
        get() = "base64ToBytes"
}
