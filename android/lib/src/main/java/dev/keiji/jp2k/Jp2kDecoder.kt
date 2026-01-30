package dev.keiji.jp2k

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * JPEG 2000 Decoder class using WebAssembly via Android JavaScriptEngine.
 *
 * This class handles the initialization of the JavaScript sandbox, loading the WebAssembly module,
 * and decoding JPEG 2000 images.
 *
 * @param config The configuration object for the decoder.
 * @param coroutineDispatcher The CoroutineDispatcher to use for background tasks. Defaults to [Dispatchers.Default].
 */
@OptIn(ExperimentalStdlibApi::class)
class Jp2kDecoder(
    private val config: Config = Config(),
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {

    private val mutex = Mutex()

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
     * Initializes the decoder.
     *
     * This method must be called before using [decodeImage]. It initializes the JavaScript sandbox
     * and loads the WebAssembly module.
     *
     * @param context The Android Context.
     * @throws Exception If initialization fails.
     */
    suspend fun init(context: Context) = mutex.withLock {
        if (_state == State.Initialized) {
            return@withLock
        }
        if (_state != State.Uninitialized) {
            throw IllegalStateException("Cannot initialize while in state: $_state")
        }
        _state = State.Initializing

        val assetManager = context.assets
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val sandboxFuture = Jp2kSandbox.get(context)

        val start = System.currentTimeMillis()
        try {
            val sandbox = sandboxFuture.await()
            val isolate = Jp2kSandbox.createIsolate(
                sandbox = sandbox,
                maxHeapSizeBytes = config.maxHeapSizeBytes,
                maxEvaluationReturnSizeBytes = config.maxEvaluationReturnSizeBytes,
            ).also { isolate ->
                Jp2kSandbox.setupConsoleCallback(isolate, sandbox, mainExecutor, TAG)
            }

            if (_state == State.Released || _state == State.Releasing) {
                isolate.close()
                throw CancellationException("Jp2kDecoder was released during initialization.")
            }
            jsIsolate = isolate

            loadWasm(isolate, assetManager)

            if (_state == State.Released || _state == State.Releasing) {
                throw CancellationException("Jp2kDecoder was released during initialization.")
            }
            _state = State.Initialized

            val time = System.currentTimeMillis() - start
            log(Log.INFO, "init() finished in $time msec")
        } catch (e: Exception) {
            if (_state != State.Released && _state != State.Releasing) {
                _state = State.Uninitialized
            }
            val time = System.currentTimeMillis() - start
            log(Log.ERROR, "init() failed in $time msec. Error: ${e.message}")
            throw e
        }
    }

    private suspend fun loadWasm(isolate: JavaScriptIsolate, assetManager: AssetManager) {
        withContext(coroutineDispatcher) {
            val wasmBytes = assetManager.open(ASSET_PATH_WASM)
                .readBytes()
            val wasmBase64String = Base64.getEncoder().encodeToString(wasmBytes)

            val script = """
            $SCRIPT_BYTES_BASE64_CONVERTER_LOCAL
            $SCRIPT_DEFINE_SET_DATA_LOCAL

            var wasmInstance;
            const wasmBuffer = globalThis.base64ToBytes('$wasmBase64String');

            $SCRIPT_IMPORT_OBJECT_LOCAL

            WebAssembly.instantiate(wasmBuffer, importObject).then(res => {
                wasmInstance = res.instance;

                $SCRIPT_DEFINE_DECODE_J2K_LOCAL
                $SCRIPT_DEFINE_GET_SIZE_LOCAL

                return "1";
            });
            """.trimIndent()

            val resultFuture = isolate.evaluateJavaScriptAsync(script)
            try {
                val result = resultFuture.await()
                if (result != "1") {
                    throw IllegalStateException("WASM instantiation failed.")
                }
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    /**
     * Initializes the decoder with initial image data.
     *
     * This method initializes the JavaScript sandbox, loads the WebAssembly module,
     * and caches the provided image data in the sandbox for subsequent operations.
     *
     * @param context The Android Context.
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @throws Exception If initialization fails.
     */
    suspend fun init(context: Context, j2kData: ByteArray) {
        init(context)

        mutex.withLock {
            if (_state != State.Initialized) {
                // Should be Initialized by init(context), unless release() was called concurrently.
                throw IllegalStateException("Jp2kDecoder is not initialized (state: $_state)")
            }

            try {
                val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }
                withContext(coroutineDispatcher) {
                    val dataBase64String = Base64.getEncoder().encodeToString(j2kData)
                    val script = "globalThis.setData('$dataBase64String');"

                    val resultFuture = isolate.evaluateJavaScriptAsync(script)
                    val result = resultFuture.await()

                    if (result != "1") {
                        val root = JSONObject(result)
                        if (root.has("errorCode")) {
                            val errorCode = root.getInt("errorCode")
                            val error = Jp2kError.fromInt(errorCode)
                            val errorMessage = if (root.has("errorMessage")) root.getString("errorMessage") else null
                            log(Log.ERROR, "Error: $error, Message: $errorMessage")
                            throw Jp2kException(error, errorMessage)
                        }
                        throw IllegalStateException("Failed to set data: $result")
                    }
                }
            } catch (e: Exception) {
                log(Log.ERROR, "init(data) failed. Error: ${e.message}")
                throw e
            }
        }
    }

    /**
     * Retrieves the size of the JPEG 2000 image without fully decoding it.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @return The [Size] of the image.
     */
    suspend fun getSize(j2kData: ByteArray): Size = mutex.withLock {
        if (_state == State.Released || _state == State.Releasing) {
            throw CancellationException("Decoder was released.")
        }
        if (_state != State.Initialized) {
            throw IllegalStateException("Cannot getSize while in state: $_state")
        }
        _state = State.Processing

        try {
            val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }

            val result = withContext(coroutineDispatcher) {
                val dataBase64String = Base64.getEncoder().encodeToString(j2kData)
                val script = "globalThis.getSize('$dataBase64String');"

                val resultFuture = isolate.evaluateJavaScriptAsync(script)
                val jsonResult = resultFuture.await()
                    ?: throw IllegalStateException("Result Future is null")

                val root = JSONObject(jsonResult)
                if (root.has("errorCode")) {
                    val errorCode = root.getInt("errorCode")
                    val error = Jp2kError.fromInt(errorCode)
                    val errorMessage = if (root.has("errorMessage")) root.getString("errorMessage") else null
                    log(Log.ERROR, "Error: $error, Message: $errorMessage")
                    throw Jp2kException(error, errorMessage)
                }

                val width = root.getInt("width")
                val height = root.getInt("height")
                Size(width, height)
            }

            restoreStateAfterDecode()
            result
        } catch (e: Exception) {
            restoreStateAfterDecode()
            if (_state == State.Released || _state == State.Releasing) {
                throw CancellationException("Decoder was released.")
            }
            throw e
        }
    }

    /**
     * Retrieves the size of the JPEG 2000 image using cached data.
     *
     * @return The [Size] of the image.
     */
    suspend fun getSize(): Size = mutex.withLock {
        if (_state == State.Released || _state == State.Releasing) {
            throw CancellationException("Decoder was released.")
        }
        if (_state != State.Initialized) {
            throw IllegalStateException("Cannot getSize while in state: $_state")
        }
        _state = State.Processing

        try {
            val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }

            val result = withContext(coroutineDispatcher) {
                val script = "globalThis.getSizeWithCache();"

                val resultFuture = isolate.evaluateJavaScriptAsync(script)
                val jsonResult = resultFuture.await()
                    ?: throw IllegalStateException("Result Future is null")

                val root = JSONObject(jsonResult)
                if (root.has("errorCode")) {
                    val errorCode = root.getInt("errorCode")
                    val error = Jp2kError.fromInt(errorCode)
                    val errorMessage = if (root.has("errorMessage")) root.getString("errorMessage") else null
                    log(Log.ERROR, "Error: $error, Message: $errorMessage")
                    throw Jp2kException(error, errorMessage)
                }

                val width = root.getInt("width")
                val height = root.getInt("height")
                Size(width, height)
            }

            restoreStateAfterDecode()
            result
        } catch (e: Exception) {
            restoreStateAfterDecode()
            if (_state == State.Released || _state == State.Releasing) {
                throw CancellationException("Decoder was released.")
            }
            throw e
        }
    }

    /**
     * Decodes a JPEG 2000 image.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @param colorFormat The desired output color format. Defaults to [ColorFormat.ARGB8888].
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        j2kData: ByteArray,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
    ): Bitmap = mutex.withLock {
        // Wait if in 'Processing' state?
        // Since we are using Mutex, we are already serialized.
        // If this function is called concurrently, the second call will wait here.
        // However, we need to check the state.
        if (_state == State.Released || _state == State.Releasing) {
            throw CancellationException("Decoder was released.")
        }
        if (_state != State.Initialized) {
            throw IllegalStateException("Cannot decodeImage while in state: $_state")
        }
        _state = State.Processing

        val start = System.currentTimeMillis()
        log(Log.INFO, "Input data length: ${j2kData.size}")

        if (j2kData.size < MIN_INPUT_SIZE) {
            restoreStateAfterDecode()
            throw IllegalArgumentException("Input data is too short")
        }

        return try {
            val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }

            val bitmap = withContext(coroutineDispatcher) {
                val measureTimes = config.logLevel != null
                val dataBase64String = Base64.getEncoder().encodeToString(j2kData)
                val script = "globalThis.decodeJ2K('$dataBase64String', ${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes);"

                val resultFuture = isolate.evaluateJavaScriptAsync(script)
                val jsonResult = resultFuture.await()
                    ?: throw IllegalStateException("Result Future is null")

                val root = JSONObject(jsonResult)
                if (root.has("errorCode")) {
                    val errorCode = root.getInt("errorCode")
                    val error = Jp2kError.fromInt(errorCode)
                    val errorMessage = if (root.has("errorMessage")) root.getString("errorMessage") else null
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

                val bmp = BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size, options)
                    ?: throw IllegalStateException("Bitmap decoding failed (returned null).")
                bmp
            }

            val time = System.currentTimeMillis() - start
            log(Log.INFO, "decodeImage() finished in $time msec")

            restoreStateAfterDecode()

            if (_state == State.Released || _state == State.Releasing) {
                throw CancellationException("Decoder was released.")
            }
            bitmap

        } catch (e: Exception) {
            val time = System.currentTimeMillis() - start
            log(Log.ERROR, "decodeImage() failed in $time msec. Error: ${e.message}")
            restoreStateAfterDecode()
            if (_state == State.Released || _state == State.Releasing) {
                throw CancellationException("Decoder was released.")
            }
            throw e
        }
    }

    /**
     * Decodes a JPEG 2000 image using cached data.
     *
     * @param colorFormat The desired output color format. Defaults to [ColorFormat.ARGB8888].
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
    ): Bitmap = mutex.withLock {
        if (_state == State.Released || _state == State.Releasing) {
            throw CancellationException("Decoder was released.")
        }
        if (_state != State.Initialized) {
            throw IllegalStateException("Cannot decodeImage while in state: $_state")
        }
        _state = State.Processing

        val start = System.currentTimeMillis()

        return try {
            val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }

            val bitmap = withContext(coroutineDispatcher) {
                val measureTimes = config.logLevel != null
                val script = "globalThis.decodeJ2KWithCache(${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes);"

                val resultFuture = isolate.evaluateJavaScriptAsync(script)
                val jsonResult = resultFuture.await()
                    ?: throw IllegalStateException("Result Future is null")

                val root = JSONObject(jsonResult)
                if (root.has("errorCode")) {
                    val errorCode = root.getInt("errorCode")
                    val error = Jp2kError.fromInt(errorCode)
                    val errorMessage = if (root.has("errorMessage")) root.getString("errorMessage") else null
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

                val bmp = BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size, options)
                    ?: throw IllegalStateException("Bitmap decoding failed (returned null).")
                bmp
            }

            val time = System.currentTimeMillis() - start
            log(Log.INFO, "decodeImage() finished in $time msec")

            restoreStateAfterDecode()

            if (_state == State.Released || _state == State.Releasing) {
                throw CancellationException("Decoder was released.")
            }
            bitmap

        } catch (e: Exception) {
            val time = System.currentTimeMillis() - start
            log(Log.ERROR, "decodeImage() failed in $time msec. Error: ${e.message}")
            restoreStateAfterDecode()
            if (_state == State.Released || _state == State.Releasing) {
                throw CancellationException("Decoder was released.")
            }
            throw e
        }
    }

    private fun restoreStateAfterDecode() {
        if (_state == State.Processing) {
            _state = State.Initialized
        }
    }


    /**
     * Retrieves memory usage statistics from the JS/WASM environment.
     *
     * @return The [MemoryUsage] statistics.
     */
    suspend fun getMemoryUsage(): MemoryUsage = mutex.withLock {
        if (_state == State.Released || _state == State.Releasing) {
             throw CancellationException("Decoder was released.")
        }
        if (_state != State.Initialized) {
            throw IllegalStateException("Cannot getMemoryUsage while in state: $_state")
        }

        _state = State.Processing

        val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }
        try {
            return withContext(coroutineDispatcher) {
                val resultFuture = isolate.evaluateJavaScriptAsync("globalThis.getMemoryUsage()")
                val jsonResult = resultFuture.await() ?: throw IllegalStateException("Result Future is null")
                val root = JSONObject(jsonResult)

                MemoryUsage(
                    wasmHeapSizeBytes = root.optLong("wasmHeapSizeBytes", 0),
                )
            }
        } finally {
            if (_state == State.Processing) {
                _state = State.Initialized
            }
        }
    }

    /**
     * Releases resources held by the decoder.
     *
     * This closes the JavaScript isolate. It should be called when the decoder is no longer needed.
     */
    fun release() {
        val isolateToClose: JavaScriptIsolate?

        // AutoCloseable.close() is not a suspend function, so we cannot use Mutex here.
        // Instead, we use synchronized block to ensure thread safety.
        synchronized(this) {
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
        } finally {
            _state = State.Released
        }
    }

    override fun close() {
        release()
    }

    private suspend fun <T> ListenableFuture<T>.await(): T {
        return suspendCancellableCoroutine { cont ->
            addListener(
                {
                    try {
                        cont.resume(get())
                    } catch (e: ExecutionException) {
                        cont.resumeWithException(e.cause ?: e)
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
                { command -> command.run() }
            )
        }
    }

    companion object {
        private const val TAG = "Jp2kDecoder"
        private const val MIN_INPUT_SIZE = 12 // Signature box length
        private const val ASSET_PATH_WASM = "openjpeg_core.wasm"

        private const val SCRIPT_BYTES_BASE64_CONVERTER_LOCAL = SCRIPT_BYTES_BASE64_CONVERTER
        private val SCRIPT_DEFINE_SET_DATA_LOCAL = SCRIPT_DEFINE_SET_DATA
        private const val SCRIPT_IMPORT_OBJECT_LOCAL = SCRIPT_IMPORT_OBJECT
        private val SCRIPT_DEFINE_DECODE_J2K_LOCAL = SCRIPT_DEFINE_DECODE_J2K
        private val SCRIPT_DEFINE_GET_SIZE_LOCAL = SCRIPT_DEFINE_GET_SIZE
    }
}
