@file:Suppress("RequiresFeature")

package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox

/**
 * Default string-based data channel used as fallback or initial channel.
 */
internal typealias DefaultJsDataChannel = Base64UrlDataChannel

/**
 * Abstraction for transferring binary data (WASM, J2K image) to the JavaScript sandbox.
 *
 * Implementations:
 * - [ProvidedNamedDataChannel]: uses [JavaScriptIsolate.provideNamedData] for direct binary transfer
 * - [Base64DataChannel]: Base64-encodes data to a JS string
 * - [Base64UrlDataChannel]: Base64Url-encodes data to a JS string
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
     * Human-readable name of the channel.
     */
    val name: String

    /**
     * Indicates whether data is transferred using string encoding (e.g., Base64, Hex, JS Array)
     * as opposed to direct binary transfer (e.g., provideNamedData).
     */
    val isStringMediated: Boolean

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
     * Binds isolate-level message channels or listeners to the provided isolate.
     */
    fun setupIsolate(isolate: JavaScriptIsolate, executor: java.util.concurrent.Executor) {}

    /**
     * Prepares the data channel before initiating a decode request (e.g. clearing queues).
     */
    fun prepareForDecode() {}

    /**
     * Name of the JS function used to encode byte arrays to string payload.
     */
    val jsEncodeFunctionName: String

    /**
     * Name of the JS function used to decode string payload to byte arrays.
     */
    val jsDecodeFunctionName: String

    /**
     * Returns a JS expression that executes getSize for the provided [j2kData].
     */
    fun getGetSizeExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        val encoded = encodePayload(j2kData).escapeJs()
        return "globalThis.getSize('$encoded');"
    }

    /**
     * Returns a JS expression that executes decodeJ2K for the provided [j2kData].
     */
    fun getDecodeJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
        maxPixels: Int,
        maxHeapSizeBytes: Long,
        colorFormatId: Int,
        measureTimes: Boolean,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ): String {
        val encoded = encodePayload(j2kData).escapeJs()
        return "globalThis.decodeJ2K('$encoded', $maxPixels, $maxHeapSizeBytes, $colorFormatId, $measureTimes, $left, $top, $right, $bottom);"
    }

    /**
     * Returns a JS expression that executes decodeJ2KRatio for the provided [j2kData].
     */
    fun getDecodeJ2KRatioExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
        maxPixels: Int,
        maxHeapSizeBytes: Long,
        colorFormatId: Int,
        measureTimes: Boolean,
        leftRatio: Float = 0f,
        topRatio: Float = 0f,
        rightRatio: Float = 0f,
        bottomRatio: Float = 0f,
    ): String {
        val encoded = encodePayload(j2kData).escapeJs()
        return "globalThis.decodeJ2KRatio('$encoded', $maxPixels, $maxHeapSizeBytes, $colorFormatId, $measureTimes, $leftRatio, $topRatio, $rightRatio, $bottomRatio);"
    }
}

/**
 * Escapes backslashes and single quotes in a string for safe embedding into JS single-quoted string literals.
 */
internal fun String.escapeJs(): String = replace("\\", "\\\\").replace("'", "\\'")

/**
 * Creates the appropriate [JSDataChannel] based on feature support.
 *
 * Checks feature flags using the provided sandbox and initializes the channel
 * via its [JSDataChannel.init] method.
 *
 * Priority order when [preferDirectBinaryTransfer] is true:
 * 1. [MessagePortDataChannel] (if [JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS] and
 *    [JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER] are supported)
 * 2. [ProvidedNamedDataChannel] (if [JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER] is supported)
 * 3. [DefaultJsDataChannel]
 *
 * @param sandbox The sandbox to check feature support.
 * @param preferDirectBinaryTransfer If true, prefers direct binary transfer when feature is supported; if false, forces Base64.
 * @return The created and initialized [JSDataChannel].
 */
internal fun createDataChannel(
    sandbox: JavaScriptSandbox,
    preferDirectBinaryTransfer: Boolean = true,
): JSDataChannel {
    return if (preferDirectBinaryTransfer) {
        if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS) &&
            sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)
        ) {
            MessagePortDataChannel().also { it.init(sandbox) }
        } else if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)) {
            ProvidedNamedDataChannel().also { it.init(sandbox) }
        } else {
            DefaultJsDataChannel().also { it.init(sandbox) }
        }
    } else {
        DefaultJsDataChannel().also { it.init(sandbox) }
    }
}
