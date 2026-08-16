@file:Suppress("RequiresFeature")

package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePort
import androidx.javascriptengine.MessagePortClient
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor

/**
 * Channel that uses MessagePort API for direct binary input (including WASM) and output transfer.
 *
 * @see JSDataChannel
 */
internal class MessagePortDataChannel : JSDataChannel {
    override val name: String = "MessagePortDataChannel"
    override val isStringMediated: Boolean = false

    private val fallbackChannel = Base64UrlDataChannel()
    private val messageQueue = ArrayBlockingQueue<ByteArray>(16)
    @Volatile
    private var messagePort: MessagePort? = null

    override fun init(sandbox: JavaScriptSandbox) {
        require(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)) {
            "JS_FEATURE_MESSAGE_PORTS not supported"
        }
    }

    override fun setupIsolate(isolate: JavaScriptIsolate, executor: Executor) {
        val client = MessagePortClient { message ->
            if (message.type == Message.TYPE_ARRAY_BUFFER) {
                val bytes = message.arrayBuffer
                messageQueue.offer(bytes)
            }
        }
        messagePort = isolate.createMessageChannel("jp2k_binary_port", executor, client)
    }

    override fun prepareForDecode() {
        messageQueue.clear()
    }

    override fun getWasmExpression(
        isolate: JavaScriptIsolate,
        wasmBytes: ByteArray,
    ): String {
        messagePort?.postMessage(Message.createArrayBufferMessage(wasmBytes))
        return "globalThis.receiveBinaryMessage()"
    }

    override fun getJ2KExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        messagePort?.postMessage(Message.createArrayBufferMessage(j2kData))
        return "(async () => { globalThis.j2kData = await globalThis.receiveBinaryMessage(); return '$INTERNAL_RESULT_SUCCESS'; })()"
    }

    override fun getGetSizeExpression(
        isolate: JavaScriptIsolate,
        j2kData: ByteArray,
    ): String {
        messagePort?.postMessage(Message.createArrayBufferMessage(j2kData))
        return "(async () => { const data = await globalThis.receiveBinaryMessage(); return globalThis.internalGetSize(data); })()"
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
        messagePort?.postMessage(Message.createArrayBufferMessage(j2kData))
        return "(async () => { const data = await globalThis.receiveBinaryMessage(); return globalThis.internalDecodeJ2K(data, $maxPixels, $maxHeapSizeBytes, $colorFormatId, $measureTimes, $left, $top, $right, $bottom, 0); })()"
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
        messagePort?.postMessage(Message.createArrayBufferMessage(j2kData))
        return "(async () => { const data = await globalThis.receiveBinaryMessage(); return globalThis.internalDecodeJ2KRatio(data, $maxPixels, $maxHeapSizeBytes, $colorFormatId, $measureTimes, $leftRatio, $topRatio, $rightRatio, $bottomRatio, 0); })()"
    }

    override fun encodePayload(data: ByteArray): String = fallbackChannel.encodePayload(data)

    override fun decodePayload(encoded: String): ByteArray = fallbackChannel.decodePayload(encoded)

    override fun retrieveDecodedBytes(encodedPayload: String): ByteArray {
        if (encodedPayload.isNotEmpty()) {
            return fallbackChannel.decodePayload(encodedPayload)
        }
        val polled = messageQueue.poll(5, java.util.concurrent.TimeUnit.SECONDS)
        if (polled != null) {
            return polled
        }
        return fallbackChannel.decodePayload(encodedPayload)
    }

    override val jsConverterScript: String
        get() = fallbackChannel.jsConverterScript

    override val jsSetupScript: String
        get() = dev.keiji.jp2k.SCRIPT_INIT_MESSAGE_PORT

    override val jsInitExpression: String
        get() = "await globalThis.initMessagePort();"

    override val jsEncodeFunctionName: String
        get() = fallbackChannel.jsEncodeFunctionName

    override val jsDecodeFunctionName: String
        get() = fallbackChannel.jsDecodeFunctionName
}
