package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import java.util.Base64

private const val SCRIPT_CONVERTER = """
            (() => {
                globalThis.bytesToBase64Url = function(bytes) {
                    return bytes.toBase64({ alphabet: 'base64url' });
                };

                globalThis.base64UrlToBytes = function(base64) {
                    return Uint8Array.fromBase64(base64, { alphabet: 'base64url' });
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
