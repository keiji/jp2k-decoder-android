package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptSandbox
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

class CreateDataChannelTest {

    @Test
    fun featureSupported_and_preferTrue_returnsProvided() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = createDataChannel(sandbox, preferDirectBinaryTransfer = true)
        assertEquals(ProvidedNamedDataChannel::class.java, channel.javaClass)
    }

    @Test
    fun featureSupported_and_preferFalse_returnsBase64() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = createDataChannel(sandbox, preferDirectBinaryTransfer = false)
        assertEquals(Base64DataChannel::class.java, channel.javaClass)
    }

    @Test
    fun featureUnsupported_returnsBase64() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(false)

        val channel = createDataChannel(sandbox, preferDirectBinaryTransfer = true)
        assertEquals(Base64DataChannel::class.java, channel.javaClass)
    }

    @Test
    fun defaultPreferTrue() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = createDataChannel(sandbox)
        assertEquals(ProvidedNamedDataChannel::class.java, channel.javaClass)
    }
}
