package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class JsArrayDataChannelTest {

    @Test
    fun encodeAndDecodePayload() {
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
    fun emptyBytes() {
        val channel = JsArrayDataChannel()
        val encoded = channel.encodePayload(ByteArray(0))
        assertEquals("[]", encoded)
        val decoded = channel.decodePayload(encoded)
        assertEquals(0, decoded.size)
    }
}
