@file:Suppress("RequiresFeature")

package dev.keiji.jp2k

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import java.util.Base64

/**
 * Abstraction for transferring binary data (WASM, J2K image) to the JavaScript sandbox.
 *
 * Implementations:
 * - [ProvidedNamedDataChannel]: uses [JavaScriptIsolate.provideNamedData] for direct binary transfer
 * - [Base64DataChannel]: Base64-encodes data to a JS string (fallback for unsupported devices)
 * - [HexDataChannel]: Hex-encodes data to a JS string
 * - [JsArrayDataChannel]: Encodes data as a JavaScript Array string
 *
 * Created at init time via [createDataChannel] — channel selection is deterministic
 * based on [JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER] support.
 *
 * Callers do NOT know or care which implementation is used; they invoke
 * [getWasmExpression], [getJ2KExpression], [encodePayload], or [decodePayload].
 */
internal interface JSDataChannel {

    /**
     * Initializes the channel with the sandbox.
     */
    fun init(sandbox: JavaScriptSandbox)

    /**
     * Provides WASM binary data and returns a JS expression that retrieves it.
     */
    fun getWasmExpression(isolate: JavaScriptIsolate, wasmBytes: ByteArray): String

    /**
     * Provides J2K data and returns a JS statement that assigns it to `globalThis.j2kData`.
     */
    fun getJ2KExpression(isolate: JavaScriptIsolate, j2kData: ByteArray): String

    /**
     * Encodes a byte array into a string payload suitable for transfer to JS.
     */
    fun encodePayload(data: ByteArray): String

    /**
     * Decodes an encoded string payload from JS back into a byte array.
     */
    fun decodePayload(encoded: String): ByteArray

    /**
     * JS script block providing the encoder/decoder converter functions for this channel.
     */
    val jsConverterScript: String

    /**
     * Name of the JS function used to encode byte arrays to string payload.
     */
    val jsEncodeFunctionName: String

    /**
     * Name of the JS function used to decode string payload to byte arrays.
     */
    val jsDecodeFunctionName: String
}

/**
 * Channel that uses [JavaScriptIsolate.provideNamedData] for direct binary data transfer.
 *
 * @see JSDataChannel
 */
internal class ProvidedNamedDataChannel : JSDataChannel {
    @Volatile
    private var sandbox: JavaScriptSandbox? = null

    private val fallbackChannel = Base64DataChannel()

    override fun init(sandbox: JavaScriptSandbox) {
        require(
            sandbox.isFeatureSupported(
                JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER
            )
        ) {
            "JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER not supported"
        }
        this.sandbox = sandbox
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        isolate.provideNamedData(PROVIDED_WASM_DATA, wasmBytes)
        return "globalThis.transferFromProvidedNamedData('$PROVIDED_WASM_DATA')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        isolate.provideNamedData(PROVIDED_J2K_DATA, j2kData)
        return "(async () => { globalThis.j2kData = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String = fallbackChannel.encodePayload(data)

    override fun decodePayload(encoded: String): ByteArray = fallbackChannel.decodePayload(encoded)

    override val jsConverterScript: String
        get() = fallbackChannel.jsConverterScript

    override val jsEncodeFunctionName: String
        get() = fallbackChannel.jsEncodeFunctionName

    override val jsDecodeFunctionName: String
        get() = fallbackChannel.jsDecodeFunctionName
}

/**
 * Channel that Base64-encodes binary data and passes it as a JS string.
 *
 * @see JSDataChannel
 */
internal class Base64DataChannel : JSDataChannel {
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
        get() = SCRIPT_BYTES_BASE64_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToBase64"

    override val jsDecodeFunctionName: String
        get() = "base64ToBytes"
}

/**
 * Channel that Hex-encodes binary data and passes it as a JS string.
 *
 * @see JSDataChannel
 */
internal class HexDataChannel : JSDataChannel {
    override fun init(sandbox: JavaScriptSandbox) {
        // No-op — Hex works on all devices
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        val encoded = encodePayload(wasmBytes)
        return "hexToBytes('$encoded')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData)
        return "(async () => { globalThis.j2kData = globalThis.hexToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val i = b.toInt() and 0xFF
            if (i < 16) sb.append('0')
            sb.append(Integer.toHexString(i))
        }
        return sb.toString()
    }

    override fun decodePayload(encoded: String): ByteArray {
        val len = encoded.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(encoded[i], 16) shl 4) + Character.digit(encoded[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    override val jsConverterScript: String
        get() = SCRIPT_BYTES_HEX_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToHex"

    override val jsDecodeFunctionName: String
        get() = "hexToBytes"
}

/**
 * Channel that encodes binary data as a JS array string and passes it to JS.
 *
 * @see JSDataChannel
 */
internal class JsArrayDataChannel : JSDataChannel {
    override fun init(sandbox: JavaScriptSandbox) {
        // No-op — JsArray works on all devices
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        val encoded = encodePayload(wasmBytes)
        return "arrayToBytes('$encoded')"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData)
        return "(async () => { globalThis.j2kData = globalThis.arrayToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun encodePayload(data: ByteArray): String {
        return data.joinToString(separator = ",", prefix = "[", postfix = "]") { (it.toInt() and 0xFF).toString() }
    }

    override fun decodePayload(encoded: String): ByteArray {
        val trimmed = encoded.trim().removePrefix("[").removeSuffix("]")
        if (trimmed.isEmpty()) return ByteArray(0)
        val parts = trimmed.split(",")
        val bytes = ByteArray(parts.size)
        for (i in parts.indices) {
            bytes[i] = parts[i].trim().toInt().toByte()
        }
        return bytes
    }

    override val jsConverterScript: String
        get() = SCRIPT_BYTES_ARRAY_CONVERTER

    override val jsEncodeFunctionName: String
        get() = "bytesToArray"

    override val jsDecodeFunctionName: String
        get() = "arrayToBytes"
}

/**
 * Creates the appropriate [JSDataChannel] based on feature support.
 *
 * Checks [JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER] using the provided sandbox
 * and initializes the channel via its [JSDataChannel.init] method.
 *
 * @param sandbox The sandbox to check feature support.
 * @param preferDirectBinaryTransfer If true, prefers direct binary transfer when feature is supported; if false, forces Base64.
 * @return [ProvidedNamedDataChannel] if supported and preferred, otherwise [Base64DataChannel].
 */
internal fun createDataChannel(
    sandbox: JavaScriptSandbox,
    preferDirectBinaryTransfer: Boolean = true,
): JSDataChannel {
    return if (preferDirectBinaryTransfer && sandbox.isFeatureSupported(
            JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER
        )
    ) {
        ProvidedNamedDataChannel().also { it.init(sandbox) }
    } else {
        Base64DataChannel().also { it.init(sandbox) }
    }
}
