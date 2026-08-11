package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class Ascii85DataChannelTest {

    @Test
    fun init_noop() {
        val sandbox = mock<JavaScriptSandbox>()
        val channel = Ascii85DataChannel()
        channel.init(sandbox)
    }

    @Test
    fun properties_values() {
        val channel = Ascii85DataChannel()
        assertEquals("Ascii85DataChannel", channel.name)
        assertEquals("bytesToAscii85", channel.jsEncodeFunctionName)
        assertEquals("ascii85ToBytes", channel.jsDecodeFunctionName)
        assertTrue(channel.jsConverterScript.contains("bytesToAscii85"))
        assertTrue(channel.jsConverterScript.contains("ascii85ToBytes"))
    }

    @Test
    fun encodeAndDecodePayload() {
        val sandbox = mock<JavaScriptSandbox>()
        val isolate = mock<JavaScriptIsolate>()
        val channel = Ascii85DataChannel()
        channel.init(sandbox)

        // Zero compression check
        val zeros = ByteArray(4)
        assertEquals("z", channel.encodePayload(zeros))
        assertArrayEquals(zeros, channel.decodePayload("z"))

        // Standard bytes roundtrip
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0x10, 0x20)
        val encoded = channel.encodePayload(bytes)
        val decoded = channel.decodePayload(encoded)
        assertArrayEquals(bytes, decoded)

        val wasmExpr = channel.getWasmExpression(isolate, bytes)
        val expectedEncoded = encoded.replace("\\", "\\\\").replace("'", "\\'")
        assertEquals("ascii85ToBytes('$expectedEncoded')", wasmExpr)

        val j2kExpr = channel.getJ2KExpression(isolate, bytes)
        assertEquals("(async () => { globalThis.j2kData = globalThis.ascii85ToBytes('$expectedEncoded'); return '$INTERNAL_RESULT_SUCCESS'; })()", j2kExpr)
    }

    @Test
    fun encodeAndDecodePayload_variousLengths() {
        val channel = Ascii85DataChannel()

        // 1 byte
        val bytes1 = byteArrayOf(0x41)
        val encoded1 = channel.encodePayload(bytes1)
        assertArrayEquals(bytes1, channel.decodePayload(encoded1))

        // 2 bytes
        val bytes2 = byteArrayOf(0x41, 0x42)
        val encoded2 = channel.encodePayload(bytes2)
        assertArrayEquals(bytes2, channel.decodePayload(encoded2))

        // 3 bytes
        val bytes3 = byteArrayOf(0x41, 0x42, 0x43)
        val encoded3 = channel.encodePayload(bytes3)
        assertArrayEquals(bytes3, channel.decodePayload(encoded3))

        // Random bytes
        val randomBytes = ByteArray(256) { (it and 0xFF).toByte() }
        val encodedRandom = channel.encodePayload(randomBytes)
        assertArrayEquals(randomBytes, channel.decodePayload(encodedRandom))
    }

    @Test
    fun emptyBytes() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Ascii85DataChannel()
        val encoded = channel.encodePayload(ByteArray(0))
        assertEquals("", encoded)
        val decoded = channel.decodePayload("")
        assertEquals(0, decoded.size)

        val wasmExpr = channel.getWasmExpression(isolate, ByteArray(0))
        assertEquals("ascii85ToBytes('')", wasmExpr)

        val j2kExpr = channel.getJ2KExpression(isolate, ByteArray(0))
        assertEquals("(async () => { globalThis.j2kData = globalThis.ascii85ToBytes(''); return '$INTERNAL_RESULT_SUCCESS'; })()", j2kExpr)
    }

    @Test
    fun escapeJs_escapesSingleQuotesAndBackslashes() {
        val testStr = "abc'def\\ghi"
        assertEquals("abc\\'def\\\\ghi", testStr.escapeJs())
    }
}
