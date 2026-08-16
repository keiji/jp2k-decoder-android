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
    fun properties_values() {
        val channel = Base64DataChannel()
        assertEquals("Base64DataChannel", channel.name)
        assertEquals("bytesToBase64", channel.jsEncodeFunctionName)
        assertEquals("base64ToBytes", channel.jsDecodeFunctionName)
        assertTrue(channel.jsConverterScript.contains("bytesToBase64"))
        assertTrue(channel.jsConverterScript.contains("base64ToBytes"))
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
    fun getWasmExpression_emptyBytes() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64DataChannel()
        val expr = channel.getWasmExpression(isolate, ByteArray(0))
        assertEquals("base64ToBytes('')", expr)
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
    fun getJ2KExpression_emptyBytes() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64DataChannel()
        val expr = channel.getJ2KExpression(isolate, ByteArray(0))
        assertEquals("(async () => { globalThis.j2kData = globalThis.base64ToBytes(''); return '$INTERNAL_RESULT_SUCCESS'; })()", expr)
    }

    @Test
    fun encodeAndDecodePayload_variousLengths() {
        val channel = Base64DataChannel()

        // 0 bytes
        val bytes0 = ByteArray(0)
        val encoded0 = channel.encodePayload(bytes0)
        assertEquals("", encoded0)
        assertArrayEquals(bytes0, channel.decodePayload(encoded0))

        // 1 byte (padding ==)
        val bytes1 = byteArrayOf(0x41)
        val encoded1 = channel.encodePayload(bytes1)
        assertEquals("QQ==", encoded1)
        assertArrayEquals(bytes1, channel.decodePayload(encoded1))

        // 2 bytes (padding =)
        val bytes2 = byteArrayOf(0x41, 0x42)
        val encoded2 = channel.encodePayload(bytes2)
        assertEquals("QUI=", encoded2)
        assertArrayEquals(bytes2, channel.decodePayload(encoded2))

        // 3 bytes (no padding)
        val bytes3 = byteArrayOf(0x41, 0x42, 0x43)
        val encoded3 = channel.encodePayload(bytes3)
        assertEquals("QUJD", encoded3)
        assertArrayEquals(bytes3, channel.decodePayload(encoded3))

        // 4 bytes (1 byte block + 1 byte remainder)
        val bytes4 = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
        val encoded4 = channel.encodePayload(bytes4)
        assertEquals("AAEC/w==", encoded4)
        assertArrayEquals(bytes4, channel.decodePayload(encoded4))

        // Large random byte array
        val randomBytes = ByteArray(256) { (it and 0xFF).toByte() }
        val encodedRandom = channel.encodePayload(randomBytes)
        assertArrayEquals(randomBytes, channel.decodePayload(encodedRandom))
    }
}
