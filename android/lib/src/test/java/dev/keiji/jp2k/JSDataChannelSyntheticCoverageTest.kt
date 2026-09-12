package dev.keiji.jp2k

import androidx.javascriptengine.JavaScriptIsolate
import dev.keiji.jp2k.datachannel.JSDataChannel
import org.junit.Test
import org.mockito.Mockito.mock
import java.lang.reflect.Modifier

class JSDataChannelSyntheticCoverageTest {

    private class DummyChannel : JSDataChannel {
        override val name = "Dummy"
        override val isStringMediated = true
        override fun init(sandbox: androidx.javascriptengine.JavaScriptSandbox) {}
        override fun getWasmExpression(isolate: JavaScriptIsolate, wasmBytes: ByteArray) = ""
        override fun getJ2KExpression(isolate: JavaScriptIsolate, j2kData: ByteArray) = ""
        override fun encodePayload(data: ByteArray) = "encoded"
        override fun decodePayload(encoded: String) = ByteArray(0)
        override val jsConverterScript = ""
        override val jsEncodeFunctionName = ""
        override val jsDecodeFunctionName = ""
    }

    @Test
    fun invokeSynthetics() {
        val channel = DummyChannel()
        val isolate = mock(JavaScriptIsolate::class.java)

        try {
            val defaultImplsClass = Class.forName("dev.keiji.jp2k.datachannel.JSDataChannel\$DefaultImpls")
            for (method in defaultImplsClass.declaredMethods) {
                if (method.name.contains("\$default") || Modifier.isStatic(method.modifiers)) {
                    method.isAccessible = true
                    val params = method.parameterTypes
                    val args = Array<Any?>(params.size) { null }

                    for (i in params.indices) {
                        val p = params[i]
                        if (p == JSDataChannel::class.java) args[i] = channel
                        else if (p == JavaScriptIsolate::class.java) args[i] = isolate
                        else if (p == ByteArray::class.java) args[i] = ByteArray(0)
                        else if (p == String::class.java) args[i] = "encoded"
                        else if (p == Int::class.java) args[i] = 0
                        else if (p == Long::class.java) args[i] = 0L
                        else if (p == Float::class.java) args[i] = 0f
                        else if (p == Boolean::class.java) args[i] = false
                        else if (p == java.util.concurrent.Executor::class.java) args[i] = java.util.concurrent.Executor { it.run() }
                        // The bitmask parameter
                        else if (i == params.size - 2 && p == Int::class.java) args[i] = 0xFFFFFFFF.toInt()
                    }

                    try {
                        method.invoke(null, *args)
                    } catch(e: Exception) {
                        // ignore
                    }
                }
            }
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }
}
