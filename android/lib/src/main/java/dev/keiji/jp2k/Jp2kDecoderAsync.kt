package dev.keiji.jp2k

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Asynchronous JPEG 2000 Decoder class using WebAssembly via Android JavaScriptEngine.
 *
 * This class provides methods to initialize and decode JPEG 2000 images asynchronously
 * using a callback mechanism. It manages its own background thread.
 *
 * @param backgroundExecutor The executor used for background operations. Defaults to a single-thread executor.
 * @param config The configuration object for the decoder.
 */
@OptIn(ExperimentalStdlibApi::class)
class Jp2kDecoderAsync(
    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor(),
    private val config: Config = Config()
) : AutoCloseable {
    private val lock = Any()
    private val executionLock = Any()

    @Volatile
    private var _state = State.Uninitialized

    /**
     * The current state of the decoder.
     */
    val state: State
        get() = _state

    private var jsIsolate: JavaScriptIsolate? = null

    /**
     * Enum representing the state of the decoder.
     */
    enum class State {
        /**
         * The decoder is not initialized.
         */
        Uninitialized,

        /**
         * The decoder is currently initializing.
         */
        Initializing,

        /**
         * The decoder is initialized and ready to decode.
         */
        Initialized,

        /**
         * The decoder is currently decoding an image.
         */
        Decoding,

        /**
         * The decoder has been terminated and cannot be used anymore.
         */
        Terminated
    }

    private fun log(priority: Int, message: String) {
        if (config.logLevel != null && priority >= config.logLevel) {
            Log.println(priority, TAG, message)
        }
    }

    /**
     * Initializes the decoder asynchronously.
     *
     * This method initializes the JavaScript sandbox and loads the WebAssembly module
     * on a background thread. The result is reported via the provided callback.
     *
     * @param context The Android Context.
     * @param callback The callback to receive the initialization result.
     */
    fun init(context: Context, callback: Callback<Unit>) {
        synchronized(lock) {
            if (_state == State.Initialized) {
                callback.onSuccess(Unit)
                return
            }
            if (_state != State.Uninitialized) {
                callback.onError(IllegalStateException("Jp2kDecoderAsync is not Uninitialized. Current state: $_state"))
                return
            }
            _state = State.Initializing
        }

        // Capture resources needed for initialization from Context
        val assetManager = context.assets
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val sandboxFuture = Jp2kSandbox.get(context)

        val start = System.currentTimeMillis()
        backgroundExecutor.execute {
            synchronized(executionLock) {
                try {
                    // Wait for sandbox connection on the background thread
                    val sandbox = sandboxFuture.get()
                    val isolate = Jp2kSandbox.createIsolate(
                        sandbox = sandbox,
                        maxHeapSizeBytes = config.maxHeapSizeBytes,
                        maxEvaluationReturnSizeBytes = config.maxEvaluationReturnSizeBytes,
                    ).also { isolate ->
                        Jp2kSandbox.setupConsoleCallback(isolate, sandbox, mainExecutor, TAG)
                    }

                    synchronized(lock) {
                        if (_state == State.Terminated) {
                            isolate.close()
                            throw CancellationException("Jp2kDecoderAsync was released during initialization.")
                        }
                        jsIsolate = isolate
                    }

                    // Load WASM
                    loadWasm(isolate, assetManager)

                    synchronized(lock) {
                        if (_state == State.Terminated) {
                            throw CancellationException("Jp2kDecoderAsync was released during initialization.")
                        }
                        _state = State.Initialized
                    }

                    val time = System.currentTimeMillis() - start
                    log(Log.INFO, "init() finished in $time msec")
                    callback.onSuccess(Unit)
                } catch (e: Exception) {
                    synchronized(lock) {
                        if (_state != State.Terminated) {
                            _state = State.Uninitialized
                        }
                    }
                    val time = System.currentTimeMillis() - start
                    log(Log.ERROR, "init() failed in $time msec. Error: ${e.message}")
                    callback.onError(e)
                }
            }
        }
    }

    private fun loadWasm(isolate: JavaScriptIsolate, assetManager: AssetManager) {
        // This runs on backgroundExecutor
        val wasmArrayString = assetManager.open(ASSET_PATH_WASM)
            .readBytes()
            .joinToString(",")

        val script = """
        var wasmInstance;
        const wasmBuffer = new Uint8Array([$wasmArrayString]);

        $SCRIPT_IMPORT_OBJECT_LOCAL

        WebAssembly.instantiate(wasmBuffer, importObject).then(res => {
            wasmInstance = res.instance;

            $SCRIPT_DEFINE_DECODE_J2K

            return "1";
        });
        """.trimIndent()

        // evaluateJavaScriptAsync returns a ListenableFuture.
        // We must wait for it synchronously on this background thread.
        val resultFuture = isolate.evaluateJavaScriptAsync(script)

        try {
            val result = resultFuture.get()
            if (result != "1") {
                throw IllegalStateException("WASM instantiation failed.")
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Decodes a JPEG 2000 image asynchronously.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @param colorFormat The desired output color format.
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(j2kData: ByteArray, colorFormat: ColorFormat = ColorFormat.ARGB8888, callback: Callback<Bitmap>) {
        synchronized(lock) {
            // Allow if Initialized OR Decoding (queueing up)
            if (_state != State.Initialized && _state != State.Decoding) {
                callback.onError(IllegalStateException("Decoder is not ready (Current state: $_state)"))
                return
            }
            // Do NOT set state to Decoding here. Wait until execution starts.
        }

        backgroundExecutor.execute {
            // Serialize execution
            synchronized(executionLock) {
                // Check state again inside the serial lock
                synchronized(lock) {
                    if (_state == State.Terminated) {
                        callback.onError(CancellationException("Decoder was released."))
                        return@execute
                    }
                    // It's possible init failed or something else happened while waiting in queue
                    if (_state != State.Initialized && _state != State.Decoding) {
                         // Note: If previous task finished, state should be Initialized.
                         // If previous task failed, state might be Initialized (if restored) or something else.
                         // But if it is Uninitialized now, we should probably fail.
                         // However, since we allowed 'Decoding' in the admission check,
                         // and we are holding executionLock, we are the only one running.
                         // So state should ideally be Initialized here (unless this is the first task).
                         // Wait, if this is the first task, it should be Initialized.
                         // If this is the second task, the first task finished and set it to Initialized.
                         // So effectively, we expect Initialized here.
                         if (_state != State.Initialized) {
                              callback.onError(IllegalStateException("Decoder state invalid before execution: $_state"))
                              return@execute
                         }
                    }
                    _state = State.Decoding
                }

                val start = System.currentTimeMillis()
                log(Log.INFO, "Input data length: ${j2kData.size}")

                if (j2kData.size < MIN_INPUT_SIZE) {
                    restoreStateAfterDecode()
                    callback.onError(IllegalArgumentException("Input data is too short"))
                    return@synchronized
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

                    restoreStateAfterDecode()
                    // Check if released during decode (unlikely due to lock, but good practice)
                    synchronized(lock) {
                         if (_state == State.Terminated) {
                             callback.onError(CancellationException("Decoder was released."))
                         } else {
                             callback.onSuccess(bitmap)
                         }
                    }

                } catch (e: Exception) {
                    val time = System.currentTimeMillis() - start
                    log(Log.ERROR, "decodeImage() failed in $time msec. Error: ${e.message}")
                    restoreStateAfterDecode()
                    synchronized(lock) {
                        if (_state == State.Terminated) {
                            callback.onError(CancellationException("Decoder was released."))
                        } else {
                            callback.onError(e)
                        }
                    }
                }
            }
        }
    }

    private fun restoreStateAfterDecode() {
        synchronized(lock) {
            if (_state == State.Decoding) {
                _state = State.Initialized
            }
        }
    }

    /**
     * Decodes a JPEG 2000 image asynchronously with default color format (ARGB 8888).
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(j2kData: ByteArray, callback: Callback<Bitmap>) {
        decodeImage(j2kData, ColorFormat.ARGB8888, callback)
    }

    /**
     * Retrieves memory usage statistics from the JS/WASM environment.
     *
     * @param callback The callback to receive the [MemoryUsage].
     */
    fun getMemoryUsage(callback: Callback<MemoryUsage>) {
        synchronized(lock) {
            if (_state != State.Initialized && _state != State.Decoding) {
                callback.onError(IllegalStateException("Decoder is not ready (Current state: $_state)"))
                return
            }
        }

        backgroundExecutor.execute {
            synchronized(executionLock) {
                synchronized(lock) {
                    if (_state == State.Terminated) {
                        callback.onError(CancellationException("Decoder was released."))
                        return@execute
                    }
                }

                try {
                    val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }
                    val resultFuture = isolate.evaluateJavaScriptAsync("globalThis.getMemoryUsage()")

                    val jsonResult =
                        resultFuture?.get() ?: throw IllegalStateException("Result Future is null")
                    val root = JSONObject(jsonResult)

                    val usage = MemoryUsage(
                        wasmHeapSizeBytes = root.optLong("wasmHeapSizeBytes", 0),
                    )
                    callback.onSuccess(usage)
                } catch (e: Exception) {
                    callback.onError(e)
                }
            }
        }
    }

    /**
     * Releases resources held by the decoder.
     *
     * This closes the JavaScript isolate and shuts down the background executor.
     */
    fun release() {
        var isolateToClose: JavaScriptIsolate? = null

        synchronized(lock) {
            if (_state == State.Terminated) {
                return
            }
            _state = State.Terminated
            isolateToClose = jsIsolate
            jsIsolate = null
        }

        try {
            isolateToClose?.close()
        } catch (e: Exception) {
            log(Log.ERROR, "Error closing isolate: ${e.message}")
        }

        if (backgroundExecutor is ExecutorService && !backgroundExecutor.isShutdown) {
            backgroundExecutor.shutdown()
        }
    }

    override fun close() {
        release()
    }

    companion object {
        private const val TAG = "Jp2kDecoderAsync"
        private const val MIN_INPUT_SIZE = 12 // Signature box length
        private const val ASSET_PATH_WASM = "openjpeg_core.wasm"

        // Script to import WASI polyfill
        // Fix: Use top-level constant from Constants.kt directly. Accessing via Class name 'Constants' is incorrect for top-level properties.
        private const val SCRIPT_IMPORT_OBJECT_LOCAL = SCRIPT_IMPORT_OBJECT

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

            globalThis.getMemoryUsage = function() {
                let wasmHeap = 0;
                try {
                    if (typeof wasmInstance !== 'undefined' && wasmInstance.exports && wasmInstance.exports.memory) {
                        wasmHeap = wasmInstance.exports.memory.buffer.byteLength;
                    }
                } catch (e) {}

                return JSON.stringify({
                    wasmHeapSizeBytes: wasmHeap,
                });
            };
        """
    }
}
