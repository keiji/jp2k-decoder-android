package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import dev.keiji.jp2k.PROVIDED_J2K_DATA
import dev.keiji.jp2k.PROVIDED_WASM_DATA
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever

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
        assertEquals("ProvidedNamedDataChannel", channel.name)
        val bytes = byteArrayOf(0x1, 0x2, 0x3)
        val encoded = channel.encodePayload(bytes)
        assertNotNull(encoded)
        val decoded = channel.decodePayload(encoded)
        assertArrayEquals(bytes, decoded)

        assertEquals("bytesToBase64", channel.jsEncodeFunctionName)
        assertEquals("base64ToBytes", channel.jsDecodeFunctionName)
        assertTrue(channel.jsConverterScript.contains("bytesToBase64"))
    }
}
