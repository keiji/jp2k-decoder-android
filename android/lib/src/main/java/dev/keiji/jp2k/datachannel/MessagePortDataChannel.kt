@file:Suppress("RequiresFeature")

package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePortClient
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor

/**
 * Channel that uses MessagePort API for direct binary output transfer and delegates to
 * [ProvidedNamedDataChannel] for direct binary input transfer.
 *
 * @see JSDataChannel
 */
internal class MessagePortDataChannel(
    private val providedNamedChannel: ProvidedNamedDataChannel = ProvidedNamedDataChannel(),
) : JSDataChannel by providedNamedChannel {
    override val name: String = "MessagePortDataChannel"
    override val isStringMediated: Boolean = false

    private val messageQueue = ArrayBlockingQueue<ByteArray>(16)

    override fun init(sandbox: JavaScriptSandbox) {
        require(
            sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS) &&
                sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER),
        ) {
            "JS_FEATURE_MESSAGE_PORTS and JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER not supported"
        }
        providedNamedChannel.init(sandbox)
    }

    override fun setupIsolate(isolate: JavaScriptIsolate, executor: Executor) {
        val client = MessagePortClient { message ->
            if (message.type == Message.TYPE_ARRAY_BUFFER) {
                val bytes = message.arrayBuffer
                messageQueue.offer(bytes)
            }
        }
        isolate.createMessageChannel("jp2k_binary_port", executor, client)
    }

    override fun prepareForDecode() {
        messageQueue.clear()
    }

    override fun decodePayload(encoded: String): ByteArray {
        val polled = messageQueue.poll()
        if (polled != null) {
            return polled
        }
        return providedNamedChannel.decodePayload(encoded)
    }
}
