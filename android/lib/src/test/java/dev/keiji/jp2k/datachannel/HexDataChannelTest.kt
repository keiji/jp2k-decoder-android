package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class HexDataChannelTest {

    @Test
    fun init_noop() {
        val sandbox = mock<JavaScriptSandbox>()
        val channel = HexDataChannel()
        channel.init(sandbox)
    }

    @Test
    fun properties_values() {
        val channel = HexDataChannel()
        assertEquals("HexDataChannel", channel.name)
        assertEquals("bytesToHex", channel.jsEncodeFunctionName)
        assertEquals("hexToBytes", channel.jsDecodeFunctionName)
        assertTrue(channel.jsConverterScript.contains("bytesToHex"))
        assertTrue(channel.jsConverterScript.contains("hexToBytes"))
    }

    @Test
    fun encodeAndDecodePayload() {
        val sandbox = mock<JavaScriptSandbox>()
        val isolate = mock<JavaScriptIsolate>()
        val channel = HexDataChannel()
        channel.init(sandbox)

        val bytes = byteArrayOf(0x00, 0x0F, 0x10, 0xFF.toByte())
        val encoded = channel.encodePayload(bytes)
        assertEquals("000f10ff", encoded)
        val decoded = channel.decodePayload(encoded)
        assertArrayEquals(bytes, decoded)

        val wasmExpr = channel.getWasmExpression(isolate, bytes)
        assertEquals("hexToBytes('000f10ff')", wasmExpr)

        val j2kExpr = channel.getJ2KExpression(isolate, bytes)
        assertEquals("(async () => { globalThis.j2kData = globalThis.hexToBytes('000f10ff'); return '$INTERNAL_RESULT_SUCCESS'; })()", j2kExpr)
    }

    @Test
    fun emptyBytes() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = HexDataChannel()
        val encoded = channel.encodePayload(ByteArray(0))
        assertEquals("", encoded)
        val decoded = channel.decodePayload("")
        assertEquals(0, decoded.size)

        val wasmExpr = channel.getWasmExpression(isolate, ByteArray(0))
        assertEquals("hexToBytes('')", wasmExpr)

        val j2kExpr = channel.getJ2KExpression(isolate, ByteArray(0))
        assertEquals("(async () => { globalThis.j2kData = globalThis.hexToBytes(''); return '$INTERNAL_RESULT_SUCCESS'; })()", j2kExpr)
    }
}
