package dev.keiji.jp2k

import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.datachannel.DefaultJsDataChannel
import dev.keiji.jp2k.datachannel.MessagePortDataChannel
import dev.keiji.jp2k.datachannel.ProvidedNamedDataChannel
import dev.keiji.jp2k.datachannel.createDataChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class JavaScriptEngineEnvironmentTest {

    private lateinit var sandbox: JavaScriptSandbox

    @Before
    fun setUp() {
        sandbox = mock()
        JavaScriptEngineEnvironment.resetForTesting()
    }

    @After
    fun tearDown() {
        JavaScriptEngineEnvironment.resetForTesting()
    }

    @Test
    fun testDefaults() {
        assertEquals(256 * 1024, JavaScriptEngineEnvironment.DEFAULT_BINDER_TRANSACTION_MAX_CHUNK_SIZE_BYTES)
        assertEquals(4L * 1024 * 1024 * 1024L, JavaScriptEngineEnvironment.DEFAULT_WASM_MAX_MEMORY_BYTES)
        assertEquals(JavaScriptEngineEnvironment.DEFAULT_BINDER_TRANSACTION_MAX_CHUNK_SIZE_BYTES, JavaScriptEngineEnvironment.binderTransactionMaxChunkSizeBytes)
        assertEquals(JavaScriptEngineEnvironment.DEFAULT_WASM_MAX_MEMORY_BYTES, JavaScriptEngineEnvironment.wasmMaxMemoryBytes)
    }

    @Test
    fun testIsFeatureSupported_DelegatesToSandbox() {
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING)).thenReturn(false)

        assertTrue(JavaScriptEngineEnvironment.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS))
        assertFalse(JavaScriptEngineEnvironment.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING))
    }

    @Test
    fun testDisableFeatureForTesting() {
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        assertTrue(JavaScriptEngineEnvironment.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS))
        assertFalse(JavaScriptEngineEnvironment.isFeatureDisabledForTesting(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS))

        JavaScriptEngineEnvironment.disableFeatureForTesting(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)

        assertTrue(JavaScriptEngineEnvironment.isFeatureDisabledForTesting(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS))
        // Returns false even though sandbox supports it
        assertFalse(JavaScriptEngineEnvironment.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS))
        // Other features unaffected
        assertTrue(JavaScriptEngineEnvironment.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER))

        // Re-enable
        JavaScriptEngineEnvironment.enableFeatureForTesting(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)
        assertFalse(JavaScriptEngineEnvironment.isFeatureDisabledForTesting(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS))
        assertTrue(JavaScriptEngineEnvironment.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS))
    }

    @Test
    fun testResetForTesting() {
        JavaScriptEngineEnvironment.disableFeatureForTesting(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)
        JavaScriptEngineEnvironment.binderTransactionMaxChunkSizeBytes = 1024
        JavaScriptEngineEnvironment.wasmMaxMemoryBytes = 1024L * 1024L

        assertTrue(JavaScriptEngineEnvironment.isFeatureDisabledForTesting(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS))
        assertEquals(1024, JavaScriptEngineEnvironment.binderTransactionMaxChunkSizeBytes)
        assertEquals(1024L * 1024L, JavaScriptEngineEnvironment.wasmMaxMemoryBytes)

        JavaScriptEngineEnvironment.resetForTesting()

        assertFalse(JavaScriptEngineEnvironment.isFeatureDisabledForTesting(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS))
        assertEquals(JavaScriptEngineEnvironment.DEFAULT_BINDER_TRANSACTION_MAX_CHUNK_SIZE_BYTES, JavaScriptEngineEnvironment.binderTransactionMaxChunkSizeBytes)
        assertEquals(JavaScriptEngineEnvironment.DEFAULT_WASM_MAX_MEMORY_BYTES, JavaScriptEngineEnvironment.wasmMaxMemoryBytes)
    }

    @Test
    fun testCreateDataChannel_RespectsDisabledFeatures() {
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)).thenReturn(true)
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)).thenReturn(true)

        // All supported -> MessagePortDataChannel
        val channel1 = createDataChannel(sandbox, preferDirectBinaryTransfer = true)
        assertTrue(channel1 is MessagePortDataChannel)

        // Disable MessagePort -> Falls back to ProvidedNamedDataChannel
        JavaScriptEngineEnvironment.disableFeatureForTesting(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS)
        val channel2 = createDataChannel(sandbox, preferDirectBinaryTransfer = true)
        assertTrue(channel2 is ProvidedNamedDataChannel)

        // Disable ProvidedNamed as well -> Falls back to DefaultJsDataChannel
        JavaScriptEngineEnvironment.disableFeatureForTesting(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)
        val channel3 = createDataChannel(sandbox, preferDirectBinaryTransfer = true)
        assertTrue(channel3 is DefaultJsDataChannel)
    }

    @Test
    fun testJp2kSandboxDelegation() {
        whenever(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING)).thenReturn(true)

        assertTrue(Jp2kSandbox.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING))

        Jp2kSandbox.disableFeatureForTesting(JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING)
        assertFalse(Jp2kSandbox.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING))

        Jp2kSandbox.enableFeatureForTesting(JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING)
        assertTrue(Jp2kSandbox.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING))

        Jp2kSandbox.disableFeatureForTesting(JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING)
        Jp2kSandbox.resetFeaturesForTesting()
        assertTrue(Jp2kSandbox.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING))
    }
}
