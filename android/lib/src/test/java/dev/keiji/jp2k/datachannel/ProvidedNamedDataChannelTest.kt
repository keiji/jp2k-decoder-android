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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor

class ProvidedNamedDataChannelTest {

    @Test
    fun init_success() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = ProvidedNamedDataChannel()
        channel.init(sandbox)
        // No exception means success
    }

    @Test
    fun init_featureUnsupported_throws() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(false)

        val channel = ProvidedNamedDataChannel()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            channel.init(sandbox)
        }
        assertEquals("JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER not supported", ex.message)
    }

    @Test
    fun getWasmExpression_callsProvideNamedData_and_returnsExpression() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val isolate = mock<JavaScriptIsolate>()
        val channel = ProvidedNamedDataChannel()
        channel.init(sandbox)

        val wasmBytes = byteArrayOf(1, 2, 3, 4)
        val expr = channel.getWasmExpression(isolate, wasmBytes)

        verify(isolate).provideNamedData(PROVIDED_WASM_DATA, wasmBytes)
        assertEquals("globalThis.transferFromProvidedNamedData('$PROVIDED_WASM_DATA')", expr)
    }

    @Test
    fun getJ2KExpression_callsProvideNamedData_and_returnsAsyncIife() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val isolate = mock<JavaScriptIsolate>()
        val channel = ProvidedNamedDataChannel()
        channel.init(sandbox)

        val j2kData = byteArrayOf(9, 8, 7)
        val expr = channel.getJ2KExpression(isolate, j2kData)

        verify(isolate).provideNamedData(PROVIDED_J2K_DATA, j2kData)
        assertEquals(
            "(async () => { globalThis.j2kData = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return '$INTERNAL_RESULT_SUCCESS'; })()",
            expr
        )
    }

    @Test
    fun getGetSizeExpression_callsProvideNamedData_and_returnsAsyncIife() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val isolate = mock<JavaScriptIsolate>()
        val channel = ProvidedNamedDataChannel()
        channel.init(sandbox)

        val j2kData = byteArrayOf(9, 8, 7)
        val expr = channel.getGetSizeExpression(isolate, j2kData)

        verify(isolate).provideNamedData(PROVIDED_J2K_DATA, j2kData)
        assertEquals(
            "(async () => { const data = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return globalThis.internalGetSize(data); })()",
            expr
        )
    }

    @Test
    fun getDecodeJ2KExpression_callsProvideNamedData_and_returnsAsyncIife() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val isolate = mock<JavaScriptIsolate>()
        val channel = ProvidedNamedDataChannel()
        channel.init(sandbox)

        val j2kData = byteArrayOf(9, 8, 7)
        val expr = channel.getDecodeJ2KExpression(
            isolate,
            j2kData,
            maxPixels = 100,
            maxHeapSizeBytes = 200L,
            colorFormatId = 1,
            measureTimes = false,
            left = 10,
            top = 20,
            right = 30,
            bottom = 40,
        )

        verify(isolate).provideNamedData(PROVIDED_J2K_DATA, j2kData)
        assertEquals(
            "(async () => { const data = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return globalThis.internalDecodeJ2K(data, 100, 200, 1, false, 10, 20, 30, 40, 0); })()",
            expr
        )
    }

    @Test
    fun getDecodeJ2KRatioExpression_callsProvideNamedData_and_returnsAsyncIife() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val isolate = mock<JavaScriptIsolate>()
        val channel = ProvidedNamedDataChannel()
        channel.init(sandbox)

        val j2kData = byteArrayOf(9, 8, 7)
        val expr = channel.getDecodeJ2KRatioExpression(
            isolate,
            j2kData,
            maxPixels = 100,
            maxHeapSizeBytes = 200L,
            colorFormatId = 1,
            measureTimes = false,
            leftRatio = 0.1f,
            topRatio = 0.2f,
            rightRatio = 0.3f,
            bottomRatio = 0.4f,
        )

        verify(isolate).provideNamedData(PROVIDED_J2K_DATA, j2kData)
        assertEquals(
            "(async () => { const data = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return globalThis.internalDecodeJ2KRatio(data, 100, 200, 1, false, 0.1, 0.2, 0.3, 0.4, 0); })()",
            expr
        )
    }

    @Test
    fun getWasmExpression_emptyBytes() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val isolate = mock<JavaScriptIsolate>()
        val channel = ProvidedNamedDataChannel()
        channel.init(sandbox)

        val expr = channel.getWasmExpression(isolate, ByteArray(0))
        verify(isolate).provideNamedData(PROVIDED_WASM_DATA, ByteArray(0))
        assertNotNull(expr)
    }

    @Test
    fun fallbackPayloadMethods() {
        val channel = ProvidedNamedDataChannel()
        val defaultChannel = DefaultJsDataChannel()
        assertEquals("ProvidedNamedDataChannel - ${defaultChannel.name}", channel.name)
        val bytes = byteArrayOf(0x1, 0x2, 0x3)
        val encoded = channel.encodePayload(bytes)
        assertNotNull(encoded)
        val decoded = channel.decodePayload(encoded)
        assertArrayEquals(bytes, decoded)

        assertEquals(defaultChannel.jsEncodeFunctionName, channel.jsEncodeFunctionName)
        assertEquals(defaultChannel.jsDecodeFunctionName, channel.jsDecodeFunctionName)
    }

    @Test
    fun setupIsolate_withMessagePortsSupported_createsMessageChannelAndReceivesBinary() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(true)

        val isolate = mock<JavaScriptIsolate>()
        val mockPort = mock<MessagePort>()
        val clientCaptor = ArgumentCaptor.forClass(MessagePortClient::class.java)

        whenever(isolate.createMessageChannel(eq(MESSAGE_PORT_NAME), any(), clientCaptor.capture())).thenReturn(mockPort)

        val channel = ProvidedNamedDataChannel()
        channel.init(sandbox)

        val executor = Executor { runnable -> runnable.run() }
        channel.setupIsolate(isolate, executor)

        verify(isolate).createMessageChannel(eq(MESSAGE_PORT_NAME), eq(executor), any())

        // Simulate incoming binary Message
        val expectedBytes = byteArrayOf(10, 20, 30, 40)
        val message = mock<Message>()
        whenever(message.type).thenReturn(Message.TYPE_ARRAY_BUFFER)
        whenever(message.arrayBuffer).thenReturn(expectedBytes)

        val client = clientCaptor.value
        client.onMessage(message)

        // Empty string payload triggers polling from queue
        val decoded = channel.decodePayload("")
        assertArrayEquals(expectedBytes, decoded)
    }

    @Test
    fun prepareForDecode_clearsPendingQueue() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(true)

        val isolate = mock<JavaScriptIsolate>()
        val mockPort = mock<MessagePort>()
        val clientCaptor = ArgumentCaptor.forClass(MessagePortClient::class.java)

        whenever(isolate.createMessageChannel(eq(MESSAGE_PORT_NAME), any(), clientCaptor.capture())).thenReturn(mockPort)

        val channel = ProvidedNamedDataChannel()
        channel.init(sandbox)

        val executor = Executor { runnable -> runnable.run() }
        channel.setupIsolate(isolate, executor)

        // Simulate incoming binary Message
        val staleBytes = byteArrayOf(1, 2, 3)
        val message = mock<Message>()
        whenever(message.type).thenReturn(Message.TYPE_ARRAY_BUFFER)
        whenever(message.arrayBuffer).thenReturn(staleBytes)

        clientCaptor.value.onMessage(message)

        // Calling prepareForDecode clears queue
        channel.prepareForDecode()

        // Non-empty string triggers fallback
        val fallbackBytes = byteArrayOf(4, 5, 6)
        val encodedFallback = channel.encodePayload(fallbackBytes)
        val decoded = channel.decodePayload(encodedFallback)
        assertArrayEquals(fallbackBytes, decoded)
    }
}
