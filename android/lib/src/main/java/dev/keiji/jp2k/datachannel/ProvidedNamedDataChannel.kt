@file:Suppress("RequiresFeature")

package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePort
import androidx.javascriptengine.MessagePortClient
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import dev.keiji.jp2k.MESSAGE_PORT_NAME
import dev.keiji.jp2k.PROVIDED_J2K_DATA
import dev.keiji.jp2k.PROVIDED_WASM_DATA
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Channel that uses [JavaScriptIsolate.provideNamedData] for direct binary data transfer.
 *
 * Also uses [MessagePort] for direct binary output retrieval when [JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS] is supported.
 *
 * @see JSDataChannel
 */
internal class ProvidedNamedDataChannel : JSDataChannel {
    override val isStringMediated: Boolean = false

    @Volatile
    private var sandbox: JavaScriptSandbox? = null

    @Volatile
    private var messagePort: MessagePort? = null

    private val pendingBytesQueue = ArrayBlockingQueue<ByteArray>(1)

    private val fallbackChannel = DefaultJsDataChannel()

    override val name: String = "ProvidedNamedDataChannel - ${fallbackChannel.name}"

    override fun init(sandbox: JavaScriptSandbox) {
        require(
            sandbox.isFeatureSupported(
                JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER,
            ),
        ) {
            "JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER not supported"
        }
        this.sandbox = sandbox
    }

    override fun setupIsolate(isolate: JavaScriptIsolate, executor: Executor) {
        val sb = sandbox ?: return
        if (sb.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)) {
            val client = MessagePortClient { message ->
                if (message.type == Message.TYPE_ARRAY_BUFFER) {
                    pendingBytesQueue.offer(message.arrayBuffer)
                }
            }
            try {
                messagePort = isolate.createMessageChannel(MESSAGE_PORT_NAME, executor, client)
            } catch (_: Exception) {
                messagePort = null
            }
        }
    }

    override fun prepareForDecode() {
        pendingBytesQueue.clear()
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

    override fun getGetSizeExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        isolate.provideNamedData(PROVIDED_J2K_DATA, j2kData)
        return "(async () => { const data = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return globalThis.internalGetSize(data); })()"
    }

    override fun getDecodeJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
        maxPixels: Int,
        maxHeapSizeBytes: Long,
        colorFormatId: Int,
        measureTimes: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): String {
        isolate.provideNamedData(PROVIDED_J2K_DATA, j2kData)
        return "(async () => { const data = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return globalThis.internalDecodeJ2K(data, $maxPixels, $maxHeapSizeBytes, $colorFormatId, $measureTimes, $left, $top, $right, $bottom, 0); })()"
    }

    override fun getDecodeJ2KRatioExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
        maxPixels: Int,
        maxHeapSizeBytes: Long,
        colorFormatId: Int,
        measureTimes: Boolean,
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float,
    ): String {
        isolate.provideNamedData(PROVIDED_J2K_DATA, j2kData)
        return "(async () => { const data = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return globalThis.internalDecodeJ2KRatio(data, $maxPixels, $maxHeapSizeBytes, $colorFormatId, $measureTimes, $leftRatio, $topRatio, $rightRatio, $bottomRatio, 0); })()"
    }

    override fun encodePayload(data: ByteArray): String = fallbackChannel.encodePayload(data)

    override fun decodePayload(encoded: String): ByteArray {
        if (encoded.isEmpty()) {
            val binaryData = pendingBytesQueue.poll(5, TimeUnit.SECONDS)
            if (binaryData != null) {
                return binaryData
            }
        }
        return fallbackChannel.decodePayload(encoded)
    }

    override val jsConverterScript: String
        get() = fallbackChannel.jsConverterScript

    override val jsEncodeFunctionName: String
        get() = fallbackChannel.jsEncodeFunctionName

    override val jsDecodeFunctionName: String
        get() = fallbackChannel.jsDecodeFunctionName
}
