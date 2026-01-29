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
import java.util.Base64
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
            if (_state == State.Released || _state == State.Releasing) {
                callback.onError(CancellationException("Decoder was released."))
                return
            }
            if (_state != State.Uninitialized) {
                callback.onError(IllegalStateException("Cannot initialize while in state: $_state"))
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
                        if (_state == State.Released || _state == State.Releasing) {
                            isolate.close()
                            throw CancellationException("Jp2kDecoderAsync was released during initialization.")
                        }
                        jsIsolate = isolate
                    }

                    // Load WASM
                    loadWasm(isolate, assetManager)

                    synchronized(lock) {
                        if (_state == State.Released || _state == State.Releasing) {
                            throw CancellationException("Jp2kDecoderAsync was released during initialization.")
                        }
                        _state = State.Initialized
                    }

                    val time = System.currentTimeMillis() - start
                    log(Log.INFO, "init() finished in $time msec")
                    callback.onSuccess(Unit)
                } catch (e: Exception) {
                    synchronized(lock) {
                        if (_state != State.Released && _state != State.Releasing) {
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
        val wasmBytes = assetManager.open(ASSET_PATH_WASM)
            .readBytes()
        val wasmBase64String = Base64.getEncoder().encodeToString(wasmBytes)

        val script = """
        $SCRIPT_BYTES_BASE64_CONVERTER

        var wasmInstance;
        const wasmBuffer = globalThis.base64ToBytes('$wasmBase64String');

        $SCRIPT_IMPORT_OBJECT

        WebAssembly.instantiate(wasmBuffer, importObject).then(res => {
            wasmInstance = res.instance;

            $SCRIPT_DEFINE_DECODE_J2K
            $SCRIPT_DEFINE_GET_SIZE

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
     * Retrieves the size of the JPEG 2000 image asynchronously without fully decoding it.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @param callback The callback to receive the [Size] or error.
     */
    fun getSize(j2kData: ByteArray, callback: Callback<Size>) {
        synchronized(lock) {
            if (_state == State.Released || _state == State.Releasing) {
                callback.onError(CancellationException("Decoder was released."))
                return
            }
            if (_state != State.Initialized && _state != State.Processing) {
                callback.onError(IllegalStateException("Cannot getSize while in state: $_state"))
                return
            }
        }

        backgroundExecutor.execute {
            synchronized(executionLock) {
                synchronized(lock) {
                    if (_state == State.Released || _state == State.Releasing) {
                        callback.onError(CancellationException("Decoder was released."))
                        return@execute
                    }
                    if (_state != State.Initialized && _state != State.Processing) {
                        callback.onError(IllegalStateException("Decoder state invalid before execution: $_state"))
                        return@execute
                    }
                    _state = State.Processing
                }

                try {
                    val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }

                    val dataBase64String = Base64.getEncoder().encodeToString(j2kData)
                    val script = "globalThis.getSize('$dataBase64String');"

                    val resultFuture = isolate.evaluateJavaScriptAsync(script)
                    val jsonResult =
                        resultFuture?.get() ?: throw IllegalStateException("Result Future is null")

                    val root = JSONObject(jsonResult)
                    if (root.has("errorCode")) {
                        val errorCode = root.getInt("errorCode")
                        val error = Jp2kError.fromInt(errorCode)
                        val errorMessage = root.optString("errorMessage", null)
                        log(Log.ERROR, "Error: $error, Message: $errorMessage")
                        throw Jp2kException(error, errorMessage)
                    }

                    val width = root.getInt("width")
                    val height = root.getInt("height")
                    val size = Size(width, height)

                    restoreStateAfterDecode()
                    synchronized(lock) {
                        if (_state == State.Released || _state == State.Releasing) {
                            callback.onError(CancellationException("Decoder was released."))
                        } else {
                            callback.onSuccess(size)
                        }
                    }

                } catch (e: Exception) {
                    restoreStateAfterDecode()
                    synchronized(lock) {
                        if (_state == State.Released || _state == State.Releasing) {
                            callback.onError(CancellationException("Decoder was released."))
                        } else {
                            callback.onError(e)
                        }
                    }
                }
            }
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
            // Allow if Initialized OR Processing (queueing up)
            if (_state != State.Initialized && _state != State.Processing) {
                callback.onError(IllegalStateException("Cannot decodeImage while in state: $_state"))
                return
            }
            // Do NOT set state to Processing here. Wait until execution starts.
        }

        backgroundExecutor.execute {
            // Serialize execution
            synchronized(executionLock) {
                // Check state again inside the serial lock
                synchronized(lock) {
                    if (_state == State.Released || _state == State.Releasing) {
                        callback.onError(CancellationException("Decoder was released."))
                        return@execute
                    }
                    // It's possible init failed or something else happened while waiting in queue
                    if (_state != State.Initialized && _state != State.Processing) {
                        callback.onError(IllegalStateException("Decoder state invalid before execution: $_state"))
                        return@execute
                    }
                    _state = State.Processing
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

                    // Optimization: Use Base64 string instead of joinToString(",") to reduce memory overhead and string size
                    val dataBase64String = Base64.getEncoder().encodeToString(j2kData)
                    val measureTimes = config.logLevel != null
                    val script = "globalThis.decodeJ2K('$dataBase64String', ${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes);"

                    val resultFuture = isolate.evaluateJavaScriptAsync(script)

                    // Block and wait for result on background thread
                    val jsonResult =
                        resultFuture?.get() ?: throw IllegalStateException("Result Future is null")

                    val root = JSONObject(jsonResult)
                    if (root.has("errorCode")) {
                        val errorCode = root.getInt("errorCode")
                        val error = Jp2kError.fromInt(errorCode)
                        val errorMessage = root.optString("errorMessage", null)
                        log(Log.ERROR, "Error: $error, Message: $errorMessage")
                        throw Jp2kException(error, errorMessage)
                    } else if (root.has("error")) {
                        val errorMsg = root.getString("error")
                        log(Log.ERROR, "Error: $errorMsg")
                        throw Jp2kException(Jp2kError.Unknown, errorMsg)
                    }

                    if (measureTimes) {
                        val timePreProcess = root.optDouble("timePreProcess", 0.0)
                        val timeWasm = root.optDouble("timeWasm", 0.0)
                        val timePostProcess = root.optDouble("timePostProcess", 0.0)
                        log(
                            Log.INFO,
                            "Pre-process: $timePreProcess ms, WASM: $timeWasm ms, Post-process: $timePostProcess ms"
                        )
                    }

                    val bmpBase64 = root.getString("bmp")
                    val bmpBytes = Base64.getDecoder().decode(bmpBase64)

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
                         if (_state == State.Released || _state == State.Releasing) {
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
                        if (_state == State.Released || _state == State.Releasing) {
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
            if (_state == State.Processing) {
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
            if (_state == State.Released || _state == State.Releasing) {
                callback.onError(CancellationException("Decoder was released."))
                return
            }
            if (_state != State.Initialized && _state != State.Processing) {
                callback.onError(IllegalStateException("Cannot getMemoryUsage while in state: $_state"))
                return
            }
        }

        backgroundExecutor.execute {
            synchronized(executionLock) {
                synchronized(lock) {
                    if (_state == State.Released || _state == State.Releasing) {
                        callback.onError(CancellationException("Decoder was released."))
                        return@execute
                    }
                    if (_state != State.Initialized && _state != State.Processing) {
                        callback.onError(IllegalStateException("Decoder state invalid before execution: $_state"))
                        return@execute
                    }
                    _state = State.Processing
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
                    restoreStateAfterDecode()
                    callback.onSuccess(usage)
                } catch (e: Exception) {
                    restoreStateAfterDecode()
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
            if (_state == State.Released || _state == State.Releasing) {
                return
            }
            _state = State.Releasing
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

        synchronized(lock) {
            _state = State.Released
        }
    }

    override fun close() {
        release()
    }

    companion object {
        private const val TAG = "Jp2kDecoderAsync"
        private const val MIN_INPUT_SIZE = 12 // Signature box length
        private const val ASSET_PATH_WASM = "openjpeg_core.wasm"
    }
}
