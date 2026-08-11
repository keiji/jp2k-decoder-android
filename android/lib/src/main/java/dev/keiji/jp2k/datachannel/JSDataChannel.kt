@file:Suppress("RequiresFeature")

package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox

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
            JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER,
        )
    ) {
        ProvidedNamedDataChannel().also { it.init(sandbox) }
    } else {
        Base64DataChannel().also { it.init(sandbox) }
    }
}
