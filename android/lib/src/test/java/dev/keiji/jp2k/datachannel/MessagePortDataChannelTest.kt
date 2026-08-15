package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.Message
import androidx.javascriptengine.MessagePortClient
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import dev.keiji.jp2k.PROVIDED_J2K_DATA
import dev.keiji.jp2k.PROVIDED_WASM_DATA
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor

class MessagePortDataChannelTest {

    @Test
    fun init_success() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = MessagePortDataChannel()
        channel.init(sandbox)
        assertEquals("MessagePortDataChannel", channel.name)
        assertEquals(false, channel.isStringMediated)
    }

    @Test
    fun init_messagePortsUnsupported_throws() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(false)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = MessagePortDataChannel()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            channel.init(sandbox)
        }
        assertEquals("JS_FEATURE_MESSAGE_PORTS and JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER not supported", ex.message)
    }

    @Test
    fun setupIsolate_and_onMessage() {
        val isolate = mock<JavaScriptIsolate>()
        val executor = Executor { command -> command.run() }
        val channel = MessagePortDataChannel()

        val captor = ArgumentCaptor.forClass(MessagePortClient::class.java)
        channel.setupIsolate(isolate, executor)

        verify(isolate).createMessageChannel(eq("jp2k_binary_port"), eq(executor), captor.capture())

        val client = captor.value
        val testBytes = byteArrayOf(10, 20, 30)
        val message = Message.createArrayBufferMessage(testBytes)

        client.onMessage(message)

        val decoded = channel.decodePayload("")
        assertArrayEquals(testBytes, decoded)
    }

    @Test
    fun prepareForDecode_clearsQueue() {
        val isolate = mock<JavaScriptIsolate>()
        val executor = Executor { command -> command.run() }
        val channel = MessagePortDataChannel()

        val captor = ArgumentCaptor.forClass(MessagePortClient::class.java)
        channel.setupIsolate(isolate, executor)
        verify(isolate).createMessageChannel(eq("jp2k_binary_port"), eq(executor), captor.capture())

        val client = captor.value
        val testBytes = byteArrayOf(10, 20, 30)
        val message = Message.createArrayBufferMessage(testBytes)
        client.onMessage(message)

        channel.prepareForDecode()

        // After clearing queue, decodePayload falls back to base64url decoding
        val fallbackInput = channel.encodePayload(byteArrayOf(1, 2))
        val decoded = channel.decodePayload(fallbackInput)
        assertArrayEquals(byteArrayOf(1, 2), decoded)
    }

    @Test
    fun expressions() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val isolate = mock<JavaScriptIsolate>()
        val channel = MessagePortDataChannel()
        channel.init(sandbox)

        val wasmBytes = byteArrayOf(1, 2, 3)
        val wasmExpr = channel.getWasmExpression(isolate, wasmBytes)
        verify(isolate).provideNamedData(PROVIDED_WASM_DATA, wasmBytes)
        assertEquals("globalThis.transferFromProvidedNamedData('$PROVIDED_WASM_DATA')", wasmExpr)

        val j2kBytes = byteArrayOf(4, 5, 6)
        val j2kExpr = channel.getJ2KExpression(isolate, j2kBytes)
        verify(isolate).provideNamedData(PROVIDED_J2K_DATA, j2kBytes)
        assertEquals("(async () => { globalThis.j2kData = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return '$INTERNAL_RESULT_SUCCESS'; })()", j2kExpr)

        val sizeExpr = channel.getGetSizeExpression(isolate, j2kBytes)
        assertEquals("(async () => { const data = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return globalThis.internalGetSize(data); })()", sizeExpr)

        val decodeExpr = channel.getDecodeJ2KExpression(
            isolate, j2kBytes, 100, 200L, 1, false, 0, 0, 0, 0
        )
        assertEquals("(async () => { const data = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return globalThis.internalDecodeJ2K(data, 100, 200, 1, false, 0, 0, 0, 0, 0); })()", decodeExpr)

        val decodeRatioExpr = channel.getDecodeJ2KRatioExpression(
            isolate, j2kBytes, 100, 200L, 1, false, 0f, 0f, 0f, 0f
        )
        assertEquals("(async () => { const data = await globalThis.transferFromProvidedNamedData('$PROVIDED_J2K_DATA'); return globalThis.internalDecodeJ2KRatio(data, 100, 200, 1, false, 0.0, 0.0, 0.0, 0.0, 0); })()", decodeRatioExpr)
    }

    @Test
    fun fallbackPayloadMethods() {
        val channel = MessagePortDataChannel()
        val bytes = byteArrayOf(0x1, 0x2, 0x3)
        val encoded = channel.encodePayload(bytes)
        assertNotNull(encoded)

        assertEquals("bytesToBase64Url", channel.jsEncodeFunctionName)
        assertEquals("base64UrlToBytes", channel.jsDecodeFunctionName)
        assertTrue(channel.jsConverterScript.contains("bytesToBase64Url"))
    }
}
