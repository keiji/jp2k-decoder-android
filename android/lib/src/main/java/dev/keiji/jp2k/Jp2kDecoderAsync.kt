package dev.keiji.jp2k

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@OptIn(ExperimentalStdlibApi::class)
class Jp2kDecoderAsync(
    context: Context,
    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor(),
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

    fun init(callback: Callback<Unit>) {
        val start = System.currentTimeMillis()
        backgroundExecutor.execute {
            try {
                // Wait for sandbox connection on the background thread
                val sandbox = sandboxFuture.get()
                jsIsolate = Jp2kSandbox.createIsolate(
                    sandbox = sandbox,
                    maxHeapSizeBytes = config.maxHeapSizeBytes,
                    maxEvaluationReturnSizeBytes = config.maxEvaluationReturnSizeBytes,
                ).also { isolate ->
                    Jp2kSandbox.setupConsoleCallback(isolate, sandbox, mainExecutor, TAG)
                }

                // Load WASM
                loadWasm()

                val time = System.currentTimeMillis() - start
                log(Log.INFO, "init() finished in $time msec")
                callback.onSuccess(Unit)
            } catch (e: Exception) {
                val time = System.currentTimeMillis() - start
                log(Log.ERROR, "init() failed in $time msec. Error: ${e.message}")
                callback.onError(e)
            }
        }
    }

    private fun loadWasm() {
        // This runs on backgroundExecutor
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

        // evaluateJavaScriptAsync returns a ListenableFuture.
        // We must wait for it synchronously on this background thread.
        val resultFuture = jsIsolate?.evaluateJavaScriptAsync(script)

        try {
            val result = resultFuture?.get()
            if (result != "1") {
                throw IllegalStateException("WASM instantiation failed.")
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    fun decodeImage(j2kData: ByteArray, colorFormat: ColorFormat = ColorFormat.ARGB8888, callback: Callback<Bitmap>) {
        backgroundExecutor.execute {
            val start = System.currentTimeMillis()
            log(Log.INFO, "Input data length: ${j2kData.size}")

            if (j2kData.size < MIN_INPUT_SIZE) {
                callback.onError(IllegalArgumentException("Input data is too short"))
                return@execute
            }

            try {
                val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }

                // Optimization: Use Hex string instead of joinToString(",") to reduce memory overhead and string size
                val dataHexString = j2kData.toHexString()
                val script = "globalThis.decodeJ2K('$dataHexString', ${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id});"

                val resultFuture = isolate.evaluateJavaScriptAsync(script)

                // Block and wait for result on background thread
                val jsonResult =
                    resultFuture?.get() ?: throw IllegalStateException("Result Future is null")

                val root = JSONObject(jsonResult)
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
                val bmpBytes = bmpHex.hexToByteArray()

                log(Log.INFO, "Output data length: ${bmpBytes.size}")

                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = when (colorFormat) {
                        ColorFormat.RGB565 -> Bitmap.Config.RGB_565
                        ColorFormat.ARGB8888 -> Bitmap.Config.ARGB_8888
                    }
                }

                val bitmap = BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size, options)

                if (bitmap == null) {
                    throw IllegalStateException("Bitmap decoding failed (returned null).")
                }

                val time = System.currentTimeMillis() - start
                log(Log.INFO, "decodeImage() finished in $time msec")

                callback.onSuccess(bitmap)

            } catch (e: Exception) {
                val time = System.currentTimeMillis() - start
                log(Log.ERROR, "decodeImage() failed in $time msec. Error: ${e.message}")
                callback.onError(e)
            }
        }
    }

    // Overload for backward compatibility / easier usage if colorFormat is omitted
    fun decodeImage(j2kData: ByteArray, callback: Callback<Bitmap>) {
        decodeImage(j2kData, ColorFormat.ARGB8888, callback)
    }

    fun release() {
        if (backgroundExecutor is java.util.concurrent.ExecutorService && !backgroundExecutor.isShutdown) {
            backgroundExecutor.execute {
                try {
                    jsIsolate?.close()
                    jsIsolate = null
                } catch (e: Exception) {
                    log(Log.ERROR, "Error closing isolate: ${e.message}")
                }

                // We share the sandbox future, so we do not close it here.
            }
            backgroundExecutor.shutdown()
        }
    }

    companion object {
        private const val TAG = "Jp2kDecoderAsync"

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

                    // Call decodeToBmp
                    const bmpPtr = exports.decodeToBmp(inputPtr, encodedBuffer.length, maxPixels, maxHeapSize, colorFormat);

                    if (bmpPtr === 0) {
                        const errorCode = exports.getLastError();
                        exports.free(inputPtr);
                        return JSON.stringify({ errorCode: errorCode });
                    }

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
