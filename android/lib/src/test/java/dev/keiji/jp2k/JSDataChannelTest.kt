package dev.keiji.jp2k

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.datachannel.Base64DataChannel
import dev.keiji.jp2k.datachannel.HexDataChannel
import dev.keiji.jp2k.datachannel.JsArrayDataChannel
import dev.keiji.jp2k.datachannel.ProvidedNamedDataChannel
import dev.keiji.jp2k.datachannel.createDataChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever

class JSDataChannelTest {

    @Test
    fun providedNamedDataChannel_init_success() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = ProvidedNamedDataChannel()
        channel.init(sandbox)
        // No exception means success
    }

    @Test
    fun providedNamedDataChannel_init_featureUnsupported_throws() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(false)

        val channel = ProvidedNamedDataChannel()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            channel.init(sandbox)
        }
        assertEquals("JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER not supported", ex.message)
    }

    @Test
    fun providedNamedDataChannel_getWasmExpression_callsProvideNamedData_and_returnsExpression() {
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
    fun providedNamedDataChannel_getJ2KExpression_callsProvideNamedData_and_returnsAsyncIife() {
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
    fun providedNamedDataChannel_getWasmExpression_emptyBytes() {
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
    fun providedNamedDataChannel_fallbackPayloadMethods() {
        val channel = ProvidedNamedDataChannel()
        val bytes = byteArrayOf(0x1, 0x2, 0x3)
        val encoded = channel.encodePayload(bytes)
        assertNotNull(encoded)
        val decoded = channel.decodePayload(encoded)
        assertArrayEquals(bytes, decoded)

        assertEquals("bytesToBase64", channel.jsEncodeFunctionName)
        assertEquals("base64ToBytes", channel.jsDecodeFunctionName)
        assertTrue(channel.jsConverterScript.contains("bytesToBase64"))
    }

    @Test
    fun base64DataChannel_init_noop() {
        val sandbox = mock<JavaScriptSandbox>()
        val channel = Base64DataChannel()
        // Should not throw
        channel.init(sandbox)
    }

    @Test
    fun base64DataChannel_getWasmExpression_encodesBase64() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64DataChannel()
        val bytes = byteArrayOf(0x41, 0x42, 0x43) // "ABC"
        val expr = channel.getWasmExpression(isolate, bytes)

        // Base64 of "ABC" is "QUJD"
        assertEquals("base64ToBytes('QUJD')", expr)
    }

    @Test
    fun base64DataChannel_getJ2KExpression_encodesBase64_and_asyncIife() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64DataChannel()
        val data = byteArrayOf(0x10, 0x20, 0x30)
        val expr = channel.getJ2KExpression(isolate, data)

        val encoded = java.util.Base64.getEncoder().encodeToString(data)
        assertEquals("(async () => { globalThis.j2kData = globalThis.base64ToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()", expr)
    }

    @Test
    fun base64DataChannel_encodeAndDecodePayload() {
        val channel = Base64DataChannel()
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
        val encoded = channel.encodePayload(bytes)
        assertEquals("AAEC/w==", encoded)
        val decoded = channel.decodePayload(encoded)
        assertArrayEquals(bytes, decoded)

        assertEquals("bytesToBase64", channel.jsEncodeFunctionName)
        assertEquals("base64ToBytes", channel.jsDecodeFunctionName)
        assertTrue(channel.jsConverterScript.contains("bytesToBase64"))
    }

    @Test
    fun base64DataChannel_getWasmExpression_emptyBytes() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64DataChannel()
        val expr = channel.getWasmExpression(isolate, ByteArray(0))
        assertEquals("base64ToBytes('')", expr)
    }

    @Test
    fun hexDataChannel_encodeAndDecodePayload() {
        val sandbox = mock<JavaScriptSandbox>()
        val isolate = mock<JavaScriptIsolate>()
        val channel = HexDataChannel()
        channel.init(sandbox)

        val bytes = byteArrayOf(0x00, 0x0F, 0x10, 0xFF.toByte())
        val encoded = channel.encodePayload(bytes)
        assertEquals("000f10ff", encoded)
        val decoded = channel.decodePayload(encoded)
        assertArrayEquals(bytes, decoded)

        assertEquals("bytesToHex", channel.jsEncodeFunctionName)
        assertEquals("hexToBytes", channel.jsDecodeFunctionName)
        assertTrue(channel.jsConverterScript.contains("bytesToHex"))

        val wasmExpr = channel.getWasmExpression(isolate, bytes)
        assertEquals("hexToBytes('000f10ff')", wasmExpr)

        val j2kExpr = channel.getJ2KExpression(isolate, bytes)
        assertEquals("(async () => { globalThis.j2kData = globalThis.hexToBytes('000f10ff'); return '$INTERNAL_RESULT_SUCCESS'; })()", j2kExpr)
    }

    @Test
    fun jsArrayDataChannel_encodeAndDecodePayload() {
        val sandbox = mock<JavaScriptSandbox>()
        val isolate = mock<JavaScriptIsolate>()
        val channel = JsArrayDataChannel()
        channel.init(sandbox)

        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
        val encoded = channel.encodePayload(bytes)
        assertEquals("[0,1,2,255]", encoded)
        val decoded = channel.decodePayload(encoded)
        assertArrayEquals(bytes, decoded)

        assertEquals("bytesToArray", channel.jsEncodeFunctionName)
        assertEquals("arrayToBytes", channel.jsDecodeFunctionName)
        assertTrue(channel.jsConverterScript.contains("bytesToArray"))

        val wasmExpr = channel.getWasmExpression(isolate, bytes)
        assertEquals("arrayToBytes('[0,1,2,255]')", wasmExpr)

        val j2kExpr = channel.getJ2KExpression(isolate, bytes)
        assertEquals("(async () => { globalThis.j2kData = globalThis.arrayToBytes('[0,1,2,255]'); return '$INTERNAL_RESULT_SUCCESS'; })()", j2kExpr)
    }

    @Test
    fun jsArrayDataChannel_emptyBytes() {
        val channel = JsArrayDataChannel()
        val encoded = channel.encodePayload(ByteArray(0))
        assertEquals("[]", encoded)
        val decoded = channel.decodePayload(encoded)
        assertEquals(0, decoded.size)
    }

    @Test
    fun createDataChannel_featureSupported_and_preferTrue_returnsProvided() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = createDataChannel(sandbox, preferDirectBinaryTransfer = true)
        assertEquals(ProvidedNamedDataChannel::class.java, channel.javaClass)
    }

    @Test
    fun createDataChannel_featureSupported_and_preferFalse_returnsBase64() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = createDataChannel(sandbox, preferDirectBinaryTransfer = false)
        assertEquals(Base64DataChannel::class.java, channel.javaClass)
    }

    @Test
    fun createDataChannel_featureUnsupported_returnsBase64() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(false)

        val channel = createDataChannel(sandbox, preferDirectBinaryTransfer = true)
        assertEquals(Base64DataChannel::class.java, channel.javaClass)
    }

    @Test
    fun createDataChannel_defaultPreferTrue() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = createDataChannel(sandbox)
        assertEquals(ProvidedNamedDataChannel::class.java, channel.javaClass)
    }
}
