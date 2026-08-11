package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class Base64DataChannelTest {

    @Test
    fun init_noop() {
        val sandbox = mock<JavaScriptSandbox>()
        val channel = Base64DataChannel()
        // Should not throw
        channel.init(sandbox)
    }

    @Test
    fun getWasmExpression_encodesBase64() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64DataChannel()
        val bytes = byteArrayOf(0x41, 0x42, 0x43) // "ABC"
        val expr = channel.getWasmExpression(isolate, bytes)

        // Base64 of "ABC" is "QUJD"
        assertEquals("base64ToBytes('QUJD')", expr)
    }

    @Test
    fun getJ2KExpression_encodesBase64_and_asyncIife() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64DataChannel()
        val data = byteArrayOf(0x10, 0x20, 0x30)
        val expr = channel.getJ2KExpression(isolate, data)

        val encoded = java.util.Base64.getEncoder().encodeToString(data)
        assertEquals("(async () => { globalThis.j2kData = globalThis.base64ToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()", expr)
    }

    @Test
    fun encodeAndDecodePayload() {
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
    fun getWasmExpression_emptyBytes() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64DataChannel()
        val expr = channel.getWasmExpression(isolate, ByteArray(0))
        assertEquals("base64ToBytes('')", expr)
    }
}
