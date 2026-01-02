package dev.keiji.jp2k

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.javascriptengine.JavaScriptIsolate
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Jp2kDecoder(
    context: Context,
    private val config: Config = Config()
) {
    private val assetManager = context.assets
    private val sandboxFuture = Jp2kSandbox.get(context)
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private var jsIsolate: JavaScriptIsolate? = null

    private fun log(priority: Int, message: String) {
        if (config.logLevel != null && priority >= config.logLevel) {
            Log.println(priority, TAG, message)
        }
    }

    suspend fun init() {
        val start = System.currentTimeMillis()
        try {
            suspendCancellableCoroutine { continuation ->
                sandboxFuture.addListener({
                    try {
                        val sandbox = sandboxFuture.get()
                        jsIsolate = Jp2kSandbox.createIsolate(
                            sandbox = sandbox,
                            maxHeapSizeBytes = config.maxHeapSizeBytes,
                            maxEvaluationReturnSizeBytes = config.maxEvaluationReturnSizeBytes,
                        ).also { isolate ->
                            Jp2kSandbox.setupConsoleCallback(isolate, sandbox, mainExecutor, TAG)
                        }
                        continuation.resume(Unit)
                    } catch (exception: Exception) {
                        continuation.resumeWithException(exception)
                    }
                }, mainExecutor)
            }
            loadWasm()
            val time = System.currentTimeMillis() - start
            log(Log.INFO, "init() finished in $time msec")
        } catch (e: Exception) {
            val time = System.currentTimeMillis() - start
            log(Log.ERROR, "init() failed in $time msec. Error: ${e.message}")
            throw e
        }
    }

    private suspend fun loadWasm() = withContext(Dispatchers.IO) {
        val wasmArrayString = assetManager.open(ASSET_PATH_WASM)
            .readBytes()
            .joinToString(",")

        val script = """
        var wasmInstance;
        const wasmBuffer = new Uint8Array([$wasmArrayString]);

        $SCRIPT_IMPORT_OBJECT

        WebAssembly.instantiate(wasmBuffer, importObject).then(res => {
            wasmInstance = res.instance;

            $SCRIPT_DEFINE_DECODE_J2K
        
            return "1";
        });
        """.trimIndent()

        val resultFuture = jsIsolate?.evaluateJavaScriptAsync(script)

        suspendCancellableCoroutine { continuation ->
            resultFuture?.addListener({
                @Suppress("BlockingMethodInNonBlockingContext")
                if (resultFuture.get() == "1") {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(IllegalStateException())
                }
            }, mainExecutor)
        }
    }

    suspend fun decodeImage(j2kData: ByteArray, colorFormat: ColorFormat = ColorFormat.ARGB8888): Bitmap {
        val start = System.currentTimeMillis()
        log(Log.INFO, "Input data length: ${j2kData.size}")

        if (j2kData.size < MIN_INPUT_SIZE) {
            throw IllegalArgumentException("Input data is too short")
        }

        try {
            val jsIsolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }

            // Optimization: Use Hex string instead of joinToString(",") to reduce memory overhead and string size
            val dataHexString = j2kData.toHexString()
            val script = "globalThis.decodeJ2K('$dataHexString', ${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id});"

            val resultFuture = jsIsolate.evaluateJavaScriptAsync(script)

            val structureJson = suspendCancellableCoroutine { continuation ->
                resultFuture?.addListener({
                    val jsonResult = resultFuture.get()
                    continuation.resume(jsonResult)
                }, mainExecutor)
            }

            val root = JSONObject(structureJson)
            if (root.has("errorCode")) {
                val errorCode = root.getInt("errorCode")
                val error = Jp2kError.fromInt(errorCode)
                log(Log.ERROR, "Error: $error")
                throw Jp2kException(error)
            } else if (root.has("error")) {
                val errorMsg = root.getString("error")
                log(Log.ERROR, "Error: $errorMsg")
                throw Jp2kException(Jp2kError.Unknown, errorMsg)
            }

            val bmpHex = root.getString("bmp")

            @OptIn(ExperimentalStdlibApi::class)
            val bmpBytes = bmpHex.hexToByteArray()

            log(Log.INFO, "Output data length: ${bmpBytes.size}")

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = when (colorFormat) {
                    ColorFormat.RGB565 -> Bitmap.Config.RGB_565
                    ColorFormat.ARGB8888 -> Bitmap.Config.ARGB_8888
                }
            }

            val bitmap = BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size, options)
                ?: throw IllegalStateException("Bitmap decoding failed (returned null).")

            val time = System.currentTimeMillis() - start
            log(Log.INFO, "decodeImage() finished in $time msec")

            return bitmap
        } catch (e: Exception) {
            val time = System.currentTimeMillis() - start
            log(Log.ERROR, "decodeImage() failed in $time msec. Error: ${e.message}")
            throw e
        }
    }

    fun release() {
        try {
            jsIsolate?.close()
            jsIsolate = null
        } catch (e: Exception) {
            log(Log.ERROR, "Error closing isolate: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Jp2kDecoder"

        private const val SCRIPT_DEFINE_DECODE_J2K = """
            globalThis.bytesToHex = function(bytes) {
                const hexChars = "0123456789abcdef";
                let output = "";
                for (let i = 0; i < bytes.length; i++) {
                    const b = bytes[i];
                    output += hexChars[(b >> 4) & 0xf];
                    output += hexChars[b & 0xf];
                }
                return output;
            };

            globalThis.hexToBytes = function(hex) {
                const len = hex.length;
                if (len === 0) return new Uint8Array(0);
                const bytes = new Uint8Array(len / 2);
                for (let i = 0; i < len; i += 2) {
                    bytes[i / 2] = parseInt(hex.substring(i, i + 2), 16);
                }
                return bytes;
            };

            globalThis.decodeJ2K = function(dataHexString, maxPixels, maxHeapSize, colorFormat) {
                try {
                    const exports = wasmInstance.exports;

                    const encodedBuffer = globalThis.hexToBytes(dataHexString);

                    const dataLength = encodedBuffer.length;
                    if (dataLength === 0) return JSON.stringify({ errorCode: -1 });
            
                    const inputPtr = exports.malloc(dataLength);
                    const heap = new Uint8Array(exports.memory.buffer);
                    
                    heap.set(encodedBuffer, inputPtr);
                    
                    // Call decodeToBmp (exported as _decodeToBmp or similar in WASM, mapped here)
                    const bmpPtr = exports.decodeToBmp(inputPtr, encodedBuffer.length, maxPixels, maxHeapSize, colorFormat);

                    if (bmpPtr === 0) {
                        const errorCode = exports.getLastError();
                        exports.free(inputPtr);
                        return JSON.stringify({ errorCode: errorCode });
                    }
                    
                    // BMP file size at offset 2
                    const view = new DataView(exports.memory.buffer);
                    const bmpSize = view.getUint32(bmpPtr + 2, true);

                    const bmpBuffer = new Uint8Array(exports.memory.buffer, bmpPtr, bmpSize);
                    const hexString = globalThis.bytesToHex(bmpBuffer);

                    exports.free(bmpPtr);
                    exports.free(inputPtr);
                
                    return JSON.stringify({
                        bmp: hexString
                    });
                } catch (e) {
                    return JSON.stringify({ error: e.toString() });
                }
            };            
        """
    }
}
