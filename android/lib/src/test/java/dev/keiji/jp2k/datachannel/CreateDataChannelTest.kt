package dev.keiji.jp2k.datachannel

import androidx.javascriptengine.JavaScriptSandbox
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

class CreateDataChannelTest {

    @Test
    fun messagePortSupported_and_preferTrue_returnsMessagePortDataChannel() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = createDataChannel(sandbox, preferDirectBinaryTransfer = true)
        assertEquals(MessagePortDataChannel::class.java, channel.javaClass)
    }

    @Test
    fun messagePortSupported_evenIfProvideUnsupported_returnsMessagePortDataChannel() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(false)

        val channel = createDataChannel(sandbox, preferDirectBinaryTransfer = true)
        assertEquals(MessagePortDataChannel::class.java, channel.javaClass)
    }

    @Test
    fun messagePortUnsupported_provideSupported_and_preferTrue_returnsProvidedNamedDataChannel() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(false)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = createDataChannel(sandbox, preferDirectBinaryTransfer = true)
        assertEquals(ProvidedNamedDataChannel::class.java, channel.javaClass)
    }

    @Test
    fun allUnsupported_and_preferTrue_returnsDefaultJsDataChannel() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(false)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(false)

        val channel = createDataChannel(sandbox, preferDirectBinaryTransfer = true)
        assertEquals(DefaultJsDataChannel::class.java, channel.javaClass)
    }

    @Test
    fun preferFalse_returnsDefaultJsDataChannel() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = createDataChannel(sandbox, preferDirectBinaryTransfer = false)
        assertEquals(DefaultJsDataChannel::class.java, channel.javaClass)
    }

    @Test
    fun defaultPreferTrue_returnsMessagePortWhenSupported() {
        val sandbox = mock<JavaScriptSandbox>()
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        val channel = createDataChannel(sandbox)
        assertEquals(MessagePortDataChannel::class.java, channel.javaClass)
    }
}
