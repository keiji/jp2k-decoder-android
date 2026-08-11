package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.INTERNAL_RESULT_SUCCESS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class Base64UrlDataChannelTest {

    @Test
    fun init_noop() {
        val sandbox = mock<JavaScriptSandbox>()
        val channel = Base64UrlDataChannel()
        // Should not throw
        channel.init(sandbox)
    }

    @Test
    fun properties_values() {
        val channel = Base64UrlDataChannel()
        assertEquals("Base64UrlDataChannel", channel.name)
        assertEquals("bytesToBase64Url", channel.jsEncodeFunctionName)
        assertEquals("base64UrlToBytes", channel.jsDecodeFunctionName)
        assertTrue(channel.jsConverterScript.contains("bytesToBase64Url"))
        assertTrue(channel.jsConverterScript.contains("base64UrlToBytes"))
    }

    @Test
    fun getWasmExpression_encodesBase64Url() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64UrlDataChannel()
        val bytes = byteArrayOf(0x41, 0x42, 0x43) // "ABC"
        val expr = channel.getWasmExpression(isolate, bytes)

        // Base64Url of "ABC" is "QUJD"
        assertEquals("base64UrlToBytes('QUJD')", expr)
    }

    @Test
    fun getWasmExpression_emptyBytes() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64UrlDataChannel()
        val expr = channel.getWasmExpression(isolate, ByteArray(0))
        assertEquals("base64UrlToBytes('')", expr)
    }

    @Test
    fun getJ2KExpression_encodesBase64Url_and_asyncIife() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64UrlDataChannel()
        val data = byteArrayOf(0x10, 0x20, 0x30)
        val expr = channel.getJ2KExpression(isolate, data)

        val encoded = java.util.Base64.getUrlEncoder().encodeToString(data)
        assertEquals("(async () => { globalThis.j2kData = globalThis.base64UrlToBytes('$encoded'); return '$INTERNAL_RESULT_SUCCESS'; })()", expr)
    }

    @Test
    fun getJ2KExpression_emptyBytes() {
        val isolate = mock<JavaScriptIsolate>()
        val channel = Base64UrlDataChannel()
        val expr = channel.getJ2KExpression(isolate, ByteArray(0))
        assertEquals("(async () => { globalThis.j2kData = globalThis.base64UrlToBytes(''); return '$INTERNAL_RESULT_SUCCESS'; })()", expr)
    }

    @Test
    fun encodeAndDecodePayload_variousLengths() {
        val channel = Base64UrlDataChannel()

        // 0 bytes
        val bytes0 = ByteArray(0)
        val encoded0 = channel.encodePayload(bytes0)
        assertEquals("", encoded0)
        assertArrayEquals(bytes0, channel.decodePayload(encoded0))

        // 1 byte
        val bytes1 = byteArrayOf(0x41)
        val encoded1 = channel.encodePayload(bytes1)
        assertEquals("QQ==", encoded1)
        assertArrayEquals(bytes1, channel.decodePayload(encoded1))

        // 2 bytes
        val bytes2 = byteArrayOf(0x41, 0x42)
        val encoded2 = channel.encodePayload(bytes2)
        assertEquals("QUI=", encoded2)
        assertArrayEquals(bytes2, channel.decodePayload(encoded2))

        // 3 bytes
        val bytes3 = byteArrayOf(0x41, 0x42, 0x43)
        val encoded3 = channel.encodePayload(bytes3)
        assertEquals("QUJD", encoded3)
        assertArrayEquals(bytes3, channel.decodePayload(encoded3))

        // Bytes containing characters that produce '-' and '_' in URL-safe Base64
        val bytesUrlSafe = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xFE.toByte())
        val encodedUrlSafe = channel.encodePayload(bytesUrlSafe)
        assertTrue(encodedUrlSafe.contains("-") || encodedUrlSafe.contains("_"))
        assertFalse(encodedUrlSafe.contains("+"))
        assertFalse(encodedUrlSafe.contains("/"))
        assertArrayEquals(bytesUrlSafe, channel.decodePayload(encodedUrlSafe))

        // Large random byte array
        val randomBytes = ByteArray(256) { (it and 0xFF).toByte() }
        val encodedRandom = channel.encodePayload(randomBytes)
        assertArrayEquals(randomBytes, channel.decodePayload(encodedRandom))
    }
}
