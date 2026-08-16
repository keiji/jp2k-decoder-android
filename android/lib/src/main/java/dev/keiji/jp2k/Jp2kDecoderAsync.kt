package dev.keiji.jp2k

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import dev.keiji.jp2k.datachannel.Base64DataChannel
import dev.keiji.jp2k.datachannel.JSDataChannel
import dev.keiji.jp2k.datachannel.createDataChannel
import dev.keiji.jp2k.datachannel.escapeJs
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
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
     * The data channel used for binary data transfer.
     *
     * Created during [init] based on feature support and never changed.
     */
    private var dataChannel: JSDataChannel = Base64DataChannel()

    private var isEvaluateWithoutTransactionLimitSupported: Boolean = true

    private inline fun log(priority: Int, message: () -> String) {
        if (config.logLevel != null && priority >= config.logLevel) {
            val msg = message().trimLines(config.maxLogLines)
            config.logger.println(priority, TAG, msg)
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
                    isEvaluateWithoutTransactionLimitSupported =
                        sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)
                    dataChannel = createDataChannel(sandbox, config.preferDirectBinaryTransfer)
                    log(Log.INFO) { "DataChannel: ${dataChannel.name}" }
                    val isolate = Jp2kSandbox.createIsolate(
                        sandbox = sandbox,
                        maxHeapSizeBytes = config.maxHeapSizeBytes,
                        maxEvaluationReturnSizeBytes = config.maxEvaluationReturnSizeBytes,
                    ).also { isolate ->
                        Jp2kSandbox.setupConsoleCallback(isolate, sandbox, mainExecutor, TAG)
                    }

                    dataChannel.setupIsolate(isolate, backgroundExecutor)

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
                    log(Log.INFO) { "init() finished in $time msec" }
                    callback.onSuccess(Unit)
                } catch (e: Exception) {
                    synchronized(lock) {
                        if (_state != State.Released && _state != State.Releasing) {
                            _state = State.Uninitialized
                        }
                    }
                    val time = System.currentTimeMillis() - start
                    log(Log.ERROR) { "init() failed in $time msec. Error: ${e.message}" }
                    callback.onError(e)
                }
            }
        }
    }

    private fun loadWasm(isolate: JavaScriptIsolate, assetManager: AssetManager) {
        // This runs on backgroundExecutor
        val wasmBytes = assetManager.open(ASSET_PATH_WASM)
            .readBytes()
        log(Log.INFO) { "DataChannel: ${dataChannel.name}" }
        log(Log.INFO) { "Input binary length: ${wasmBytes.size}" }

        // Stage 1: Initialize JavaScript environment, helper functions, and establish data channel.
        // When using MessagePort, messages sent before the JavaScript onmessage handler is attached
        // will be silently dropped by Android JavaScriptEngine.
        // Therefore, we evaluate the channel's setup script and await its initialization expression first.
        val setupScript = """
            ${dataChannel.jsConverterScript}
            ${dataChannel.jsSetupScript}
            $SCRIPT_DEFINE_INPUT_CHUNKS
            $SCRIPT_DEFINE_SET_DATA
            $SCRIPT_IMPORT_OBJECT

            (async () => {
                ${dataChannel.jsInitExpression}
                return "$INTERNAL_RESULT_SUCCESS";
            })();
        """.trimIndent()

        val setupResultFuture = isolate.evaluateJavaScriptAsync(setupScript)
        try {
            val setupResult = setupResultFuture.get()
            if (setupResult != INTERNAL_RESULT_SUCCESS) {
                ensureNotEmpty(setupResult, "Success indicator")
                throw IllegalStateException("WASM instantiation failed.")
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

        // Stage 2: Transmit WASM binary and instantiate the WebAssembly module.
        val wasmExpression = dataChannel.getWasmExpression(isolate, wasmBytes)
        log(Log.INFO) { "WASM expression: $wasmExpression" }

        val instantiateScript = """
            var wasmInstance;

            (async () => {
                const wasmBuffer = await $wasmExpression;

                const res = await WebAssembly.instantiate(wasmBuffer, importObject);
                wasmInstance = res.instance;

                $SCRIPT_DEFINE_DECODE_J2K
                $SCRIPT_DEFINE_GET_SIZE

                return "$INTERNAL_RESULT_SUCCESS";
            })();
        """.trimIndent()

        // evaluateJavaScriptAsync returns a ListenableFuture.
        // We must wait for it synchronously on this background thread
        val resultFuture = isolate.evaluateJavaScriptAsync(instantiateScript)

        try {
            val result = resultFuture.get()
            if (result != INTERNAL_RESULT_SUCCESS) {
                ensureNotEmpty(result, "Success indicator")
                throw IllegalStateException("WASM instantiation failed.")
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun validateInputSize(size: Int): Exception? {
        if (size.toLong() > config.maxHeapSizeBytes || size.toLong() > WASM_MAX_MEMORY_BYTES) {
            return Jp2kException(
                Jp2kError.InputDataSize,
                "Input data size ($size bytes) exceeds maximum allowable size (${config.maxHeapSizeBytes} bytes)",
            )
        }
        return null
    }

    private fun transferInputInChunks(isolate: JavaScriptIsolate, encoded: String) {
        isolate.evaluateJavaScriptAsync("globalThis.clearInputChunks();").get()
        var offset = 0
        while (offset < encoded.length) {
            val end = minOf(offset + BINDER_TRANSACTION_MAX_CHUNK_SIZE_BYTES, encoded.length)
            val chunk = encoded.substring(offset, end).escapeJs()
            isolate.evaluateJavaScriptAsync("globalThis.appendInputChunk('$chunk');").get()
            offset = end
        }
    }

    /**
     * Precaches the image data in the JavaScript sandbox for subsequent operations.
     *
     * This method must be called after [init]. It caches the provided image data
     * in the sandbox, allowing [getSize] and [decodeImage] to be called without arguments.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @param callback The callback to receive the precache result.
     */
    fun precache(j2kData: ByteArray, callback: Callback<Unit>) {
        val validationError = validateInputSize(j2kData.size)
        if (validationError != null) {
            callback.onError(validationError)
            return
        }

        synchronized(lock) {
            if (_state == State.Released || _state == State.Releasing) {
                callback.onError(CancellationException("Decoder was released."))
                return
            }
            if (_state != State.Initialized && _state != State.Processing) {
                callback.onError(IllegalStateException("Cannot precache while in state: $_state"))
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
                    log(Log.INFO) { "DataChannel: ${dataChannel.name}" }
                    log(Log.INFO) { "Input binary length: ${j2kData.size}" }

                    val result = if (!isEvaluateWithoutTransactionLimitSupported && dataChannel.isStringMediated) {
                        val encoded = dataChannel.encodePayload(j2kData)
                        transferInputInChunks(isolate, encoded)
                        val resultFuture = isolate.evaluateJavaScriptAsync("globalThis.setDataFromChunks();")
                        resultFuture.get()
                    } else {
                        val script = dataChannel.getJ2KExpression(isolate, j2kData)
                        log(Log.INFO) { "J2K expression: $script" }

                        val resultFuture = isolate.evaluateJavaScriptAsync(script)
                        resultFuture.get()
                    }

                    if (result != INTERNAL_RESULT_SUCCESS) {
                        ensureNotEmpty(result, "Success indicator or JSON error")

                        val root = JSONObject(result)
                        if (root.has("errorCode")) {
                            val errorCode = root.getInt("errorCode")
                            val error = Jp2kError.fromInt(errorCode)
                            val errorMessage = if (root.has("errorMessage")) root.getString("errorMessage") else null
                            log(Log.ERROR) { "Error: $error, Message: $errorMessage" }
                            throw Jp2kException(error, errorMessage)
                        }
                        throw IllegalStateException("Failed to set data: $result")
                    }

                    restoreStateAfterDecode()
                    synchronized(lock) {
                        if (_state == State.Released || _state == State.Releasing) {
                            callback.onError(CancellationException("Decoder was released."))
                        } else {
                            callback.onSuccess(Unit)
                        }
                    }
                } catch (e: Exception) {
                    log(Log.ERROR) { "precache() failed. Error: ${e.message}" }
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
     * Retrieves the size of the JPEG 2000 image asynchronously using cached data.
     *
     * @param callback The callback to receive the [Size] or error.
     */
    fun getSize(callback: Callback<Size>) {
        executeGetSize(callback) { isolate ->
            isolate.evaluateJavaScriptAsync("globalThis.getSizeWithCache();").get()
        }
    }

    private fun logInputDataInfo(j2kData: ByteArray) {
        log(Log.INFO) { "DataChannel: ${dataChannel.name}" }
        log(Log.INFO) { "Input data length: ${j2kData.size} bytes" }
    }

    private fun logEncodedInputInfo(encodedPayload: String) {
        if (dataChannel.isStringMediated) {
            log(Log.INFO) { "Input encoded content length: ${encodedPayload.length} chars" }
            log(Log.INFO) { "Input encoded content (64 chars per line):\n${encodedPayload.chunked64()}" }
        }
    }

    fun getSize(j2kData: ByteArray, callback: Callback<Size>) {
        val validationError = validateInputSize(j2kData.size)
        if (validationError != null) {
            callback.onError(validationError)
            return
        }

        logInputDataInfo(j2kData)
        val encoded = dataChannel.encodePayload(j2kData)
        logEncodedInputInfo(encoded)

        executeGetSize(callback) { isolate ->
            if (!isEvaluateWithoutTransactionLimitSupported && dataChannel.isStringMediated) {
                transferInputInChunks(isolate, encoded)
                isolate.evaluateJavaScriptAsync("globalThis.getSizeFromChunks();").get()
            } else {
                isolate.evaluateJavaScriptAsync("globalThis.getSize('$encoded');").get()
            }
        }
    }

    private fun executeGetSize(
        callback: Callback<Size>,
        evaluate: (JavaScriptIsolate) -> String,
    ) {
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

                    val jsonResult = ensureNotEmpty(evaluate(isolate), "JSON")

                    val root = JSONObject(jsonResult)
                    if (root.has("errorCode")) {
                        val errorCode = root.getInt("errorCode")
                        if (errorCode == Jp2kError.CacheDataMissing.code) {
                            throw IllegalStateException("No data cached")
                        }
                        val error = Jp2kError.fromInt(errorCode)
                        val errorMessage =
                            if (root.has("errorMessage")) root.getString("errorMessage") else null
                        log(Log.ERROR) { "Error: $error, Message: $errorMessage" }
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
     * Decodes a JPEG 2000 image asynchronously using cached data.
     *
     * @param colorFormat The desired output color format.
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(colorFormat: ColorFormat = ColorFormat.ARGB8888, callback: Callback<Bitmap>) {
        decodeImage(0, 0, 0, 0, colorFormat, callback)
    }

    /**
     * Decodes a specific region of a JPEG 2000 image asynchronously using cached data.
     *
     * @param left The left coordinate of the region.
     * @param top The top coordinate of the region.
     * @param right The right coordinate of the region.
     * @param bottom The bottom coordinate of the region.
     * @param colorFormat The desired output color format.
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
        callback: Callback<Bitmap>
    ) {
        val measureTimes = config.logLevel != null
        val kotlinStartTime = System.currentTimeMillis()
        val chunkedOutput = !isEvaluateWithoutTransactionLimitSupported
        val script =
            "globalThis.decodeJ2KWithCache(${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"
        executeDecodeImage(colorFormat, callback) { isolate ->
            isolate.evaluateJavaScriptAsync(script).get()
        }
    }

    /**
     * Decodes a specific region of a JPEG 2000 image asynchronously using cached data with default color format (ARGB 8888).
     *
     * @param left The left coordinate of the region.
     * @param top The top coordinate of the region.
     * @param right The right coordinate of the region.
     * @param bottom The bottom coordinate of the region.
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        callback: Callback<Bitmap>
    ) {
        decodeImage(left, top, right, bottom, ColorFormat.ARGB8888, callback)
    }

    /**
     * Decodes a specific region of a JPEG 2000 image asynchronously using cached data.
     *
     * @param region The region to decode.
     * @param colorFormat The desired output color format.
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(
        region: Rect,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
        callback: Callback<Bitmap>
    ) {
        decodeImage(region.left, region.top, region.right, region.bottom, colorFormat, callback)
    }

    /**
     * Decodes a specific region of a JPEG 2000 image asynchronously using cached data with default color format (ARGB 8888).
     *
     * @param region The region to decode.
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(
        region: Rect,
        callback: Callback<Bitmap>
    ) {
        decodeImage(region, ColorFormat.ARGB8888, callback)
    }

    /**
     * Decodes a specific region of a JPEG 2000 image asynchronously using cached data.
     *
     * @param region The region to decode.
     * @param colorFormat The desired output color format.
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(
        region: RectF,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
        callback: Callback<Bitmap>
    ) {
        decodeImage(region.left, region.top, region.right, region.bottom, colorFormat, callback)
    }

    /**
     * Decodes a specific region of a JPEG 2000 image asynchronously using cached data with default color format (ARGB 8888).
     *
     * @param region The region to decode.
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(
        region: RectF,
        callback: Callback<Bitmap>
    ) {
        decodeImage(region, ColorFormat.ARGB8888, callback)
    }

    /**
     * Decodes a specific region of a JPEG 2000 image asynchronously using cached data.
     *
     * @param left The left coordinate ratio (0.0 - 1.0).
     * @param top The top coordinate ratio (0.0 - 1.0).
     * @param right The right coordinate ratio (0.0 - 1.0).
     * @param bottom The bottom coordinate ratio (0.0 - 1.0).
     * @param colorFormat The desired output color format.
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
        callback: Callback<Bitmap>
    ) {
        if (!validateRatio(left, top, right, bottom, callback)) {
            return
        }

        val measureTimes = config.logLevel != null
        val kotlinStartTime = System.currentTimeMillis()
        val chunkedOutput = !isEvaluateWithoutTransactionLimitSupported
        val script =
            "globalThis.decodeJ2KWithCacheRatio(${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"
        executeDecodeImage(colorFormat, callback) { isolate ->
            isolate.evaluateJavaScriptAsync(script).get()
        }
    }

    /**
     * Decodes a specific region of a JPEG 2000 image asynchronously using cached data with default color format (ARGB 8888).
     *
     * @param left The left coordinate ratio (0.0 - 1.0).
     * @param top The top coordinate ratio (0.0 - 1.0).
     * @param right The right coordinate ratio (0.0 - 1.0).
     * @param bottom The bottom coordinate ratio (0.0 - 1.0).
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        callback: Callback<Bitmap>
    ) {
        decodeImage(left, top, right, bottom, ColorFormat.ARGB8888, callback)
    }

    /**
     * Decodes a JPEG 2000 image asynchronously.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @param colorFormat The desired output color format.
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(
        j2kData: ByteArray,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
        callback: Callback<Bitmap>
    ) {
        decodeImage(j2kData, 0, 0, 0, 0, colorFormat, callback)
    }

    fun decodeImage(
        j2kData: ByteArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
        callback: Callback<Bitmap>
    ) {
        if (j2kData.size < MIN_INPUT_SIZE) {
            callback.onError(IllegalArgumentException("Input data is too short"))
            return
        }
        val validationError = validateInputSize(j2kData.size)
        if (validationError != null) {
            callback.onError(validationError)
            return
        }

        logInputDataInfo(j2kData)

        val measureTimes = config.logLevel != null
        val encoded = dataChannel.encodePayload(j2kData)
        logEncodedInputInfo(encoded)

        val kotlinStartTime = System.currentTimeMillis()
        val chunkedOutput = !isEvaluateWithoutTransactionLimitSupported

        executeDecodeImage(colorFormat, callback, j2kData.size.toLong()) { isolate ->
            if (!isEvaluateWithoutTransactionLimitSupported && dataChannel.isStringMediated) {
                transferInputInChunks(isolate, encoded)
                isolate.evaluateJavaScriptAsync(
                    "globalThis.decodeJ2KFromChunks(${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"
                ).get()
            } else {
                val script =
                    "globalThis.decodeJ2K('$encoded', ${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"
                isolate.evaluateJavaScriptAsync(script).get()
            }
        }
    }

    fun decodeImage(
        j2kData: ByteArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        callback: Callback<Bitmap>
    ) {
        decodeImage(j2kData, left, top, right, bottom, ColorFormat.ARGB8888, callback)
    }

    fun decodeImage(
        j2kData: ByteArray,
        region: Rect,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
        callback: Callback<Bitmap>
    ) {
        decodeImage(j2kData, region.left, region.top, region.right, region.bottom, colorFormat, callback)
    }

    fun decodeImage(
        j2kData: ByteArray,
        region: Rect,
        callback: Callback<Bitmap>
    ) {
        decodeImage(j2kData, region, ColorFormat.ARGB8888, callback)
    }

    fun decodeImage(
        j2kData: ByteArray,
        region: RectF,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
        callback: Callback<Bitmap>
    ) {
        decodeImage(j2kData, region.left, region.top, region.right, region.bottom, colorFormat, callback)
    }

    fun decodeImage(
        j2kData: ByteArray,
        region: RectF,
        callback: Callback<Bitmap>
    ) {
        decodeImage(j2kData, region, ColorFormat.ARGB8888, callback)
    }

    fun decodeImage(
        j2kData: ByteArray,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
        callback: Callback<Bitmap>
    ) {
        if (j2kData.size < MIN_INPUT_SIZE) {
            callback.onError(IllegalArgumentException("Input data is too short"))
            return
        }
        val validationError = validateInputSize(j2kData.size)
        if (validationError != null) {
            callback.onError(validationError)
            return
        }
        if (!validateRatio(left, top, right, bottom, callback)) {
            return
        }

        logInputDataInfo(j2kData)

        val measureTimes = config.logLevel != null
        val encoded = dataChannel.encodePayload(j2kData)
        logEncodedInputInfo(encoded)

        val kotlinStartTime = System.currentTimeMillis()
        val chunkedOutput = !isEvaluateWithoutTransactionLimitSupported

        executeDecodeImage(colorFormat, callback, j2kData.size.toLong()) { isolate ->
            if (!isEvaluateWithoutTransactionLimitSupported && dataChannel.isStringMediated) {
                transferInputInChunks(isolate, encoded)
                isolate.evaluateJavaScriptAsync(
                    "globalThis.decodeJ2KRatioFromChunks(${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"
                ).get()
            } else {
                val script =
                    "globalThis.decodeJ2KRatio('$encoded', ${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"
                isolate.evaluateJavaScriptAsync(script).get()
            }
        }
    }

    fun decodeImage(
        j2kData: ByteArray,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        callback: Callback<Bitmap>
    ) {
        decodeImage(j2kData, left, top, right, bottom, ColorFormat.ARGB8888, callback)
    }

    private fun validateRatio(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        callback: Callback<Bitmap>
    ): Boolean {
        if (left < 0.0f || left > 1.0f || top < 0.0f || top > 1.0f ||
            right < 0.0f || right > 1.0f || bottom < 0.0f || bottom > 1.0f
        ) {
            callback.onError(IllegalArgumentException("Ratio must be 0.0 - 1.0"))
            return false
        }
        return true
    }

    private fun executeDecodeImage(
        colorFormat: ColorFormat,
        callback: Callback<Bitmap>,
        inputSize: Long = 0L,
        evaluate: (JavaScriptIsolate) -> String,
    ) {
        synchronized(lock) {
            if (_state != State.Initialized && _state != State.Processing) {
                callback.onError(IllegalStateException("Cannot decodeImage while in state: $_state"))
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

                dataChannel.prepareForDecode()

                val start = System.currentTimeMillis()

                try {
                    val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }

                    val measureTimes = config.logLevel != null
                    val transferStart = if (measureTimes) System.nanoTime() else 0L

                    val jsonResult = ensureNotEmpty(evaluate(isolate), "JSON")
                    val kotlinReceiveTimeMs = System.currentTimeMillis()
                    val transferEnd = if (measureTimes) System.nanoTime() else 0L

                    val root = JSONObject(jsonResult)
                    if (root.has("errorCode")) {
                        val errorCode = root.getInt("errorCode")
                        if (errorCode == Jp2kError.CacheDataMissing.code) {
                            throw IllegalStateException("No data cached")
                        }
                        val error = Jp2kError.fromInt(errorCode)
                        val errorMessage =
                            if (root.has("errorMessage")) root.getString("errorMessage") else null
                        log(Log.ERROR) { "Error: $error, Message: $errorMessage" }

                        if (error == Jp2kError.RegionOutOfBounds) {
                            throw RegionOutOfBoundsException(errorMessage)
                        }

                        throw Jp2kException(error, errorMessage)
                    } else if (root.has("error")) {
                        val errorMsg = root.getString("error")
                        log(Log.ERROR) { "Error: $errorMsg" }
                        throw Jp2kException(Jp2kError.Unknown, errorMsg)
                    }

                    val bmpBase64 = if (root.optBoolean("isChunked", false)) {
                        val outputSize = root.getInt("outputSize")
                        val sb = java.lang.StringBuilder(outputSize)
                        var offset = 0
                        while (offset < outputSize) {
                            val length = minOf(BINDER_TRANSACTION_MAX_CHUNK_SIZE_BYTES, outputSize - offset)
                            val chunk = isolate.evaluateJavaScriptAsync("globalThis.getOutputChunk($offset, $length);").get()
                            sb.append(chunk)
                            offset += length
                        }
                        isolate.evaluateJavaScriptAsync("globalThis.clearOutput();").get()
                        sb.toString()
                    } else {
                        root.optString("bmp", "")
                    }

                    if (dataChannel.isStringMediated) {
                        log(Log.INFO) { "Output encoded content length: ${bmpBase64.length} chars" }
                        log(Log.INFO) { "Output encoded content (64 chars per line):\n${bmpBase64.chunked64()}" }
                    }

                    val kotlinDecodeStart = System.nanoTime()
                    val bmpBytes = dataChannel.retrieveDecodedBytes(bmpBase64)

                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = when (colorFormat) {
                            ColorFormat.RGB565 -> Bitmap.Config.RGB_565
                            ColorFormat.ARGB8888 -> Bitmap.Config.ARGB_8888
                        }
                    }

                    val bitmap =
                        BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size, options)

                    val kotlinDecodeTimeMs = (System.nanoTime() - kotlinDecodeStart) / 1_000_000.0

                    log(Log.INFO) { "Output data length: ${bmpBytes.size} bytes" }

                    if (measureTimes) {
                        val timePreProcess = root.optDouble("timePreProcess", 0.0)
                        val timeWasm = root.optDouble("timeWasm", 0.0)
                        val timePostProcess = root.optDouble("timePostProcess", 0.0)
                        val dataTransferTimeMs = (transferEnd - transferStart) / 1_000_000.0
                        val jsDecodeTimeMs = root.optDouble("timeBase64Decode", 0.0)
                        val jsEncodeTimeMs = root.optDouble("timeBase64Encode", 0.0)
                        val wasmHeapSizeBytes = root.optLong("wasmHeapSizeBytes", 0)
                        val totalMs = (System.currentTimeMillis() - start).toDouble()

                        val inputTransferDelayMs = root.optDouble("inputTransferDelayMs", 0.0)
                        val jsFinishTimeMs = root.optLong("jsFinishTimeMs", 0L)
                        val outputTransferDelayMs = if (jsFinishTimeMs > 0) Math.max(0.0, (kotlinReceiveTimeMs - jsFinishTimeMs).toDouble()) else 0.0

                        log(Log.INFO) { "Input transfer start delay (Kotlin -> JS start): ${"%.2f".format(inputTransferDelayMs)} ms" }
                        if (dataChannel.isStringMediated) {
                            log(Log.INFO) { "Input JS decode time: ${"%.2f".format(jsDecodeTimeMs)} ms" }
                        }
                        log(Log.INFO) { "Output transfer delay (JS finish -> Kotlin receive): ${"%.2f".format(outputTransferDelayMs)} ms" }
                        log(Log.INFO) { "Output Kotlin decode time: ${"%.2f".format(kotlinDecodeTimeMs)} ms" }

                        val metrics = PerformanceMetrics(
                            inputDataSizeBytes = inputSize,
                            dataTransferTimeMs = dataTransferTimeMs,
                            jsDecodeTimeMs = jsDecodeTimeMs,
                            wasmProcessingTimeMs = timeWasm,
                            jsEncodeTimeMs = jsEncodeTimeMs,
                            outputDataSizeBytes = bmpBytes.size.toLong(),
                            wasmHeapSizeBytes = wasmHeapSizeBytes,
                            totalProcessingTimeMs = totalMs,
                        )

                        val inputStr = "%d".format(metrics.inputDataSizeBytes)
                        val outputStr = "%d".format(metrics.outputDataSizeBytes)
                        val totalMsStr = "%.0f".format(metrics.totalProcessingTimeMs)
                        val transferMsStr = "%.0f".format(metrics.dataTransferTimeMs)
                        val decodeMsStr = "%.0f".format(metrics.jsDecodeTimeMs)
                        val encodeMsStr = "%.0f".format(metrics.jsEncodeTimeMs)
                        val wasmHeapMB = metrics.wasmHeapSizeBytes / (1024 * 1024)

                        log(Log.INFO) {
                            "Performance: inputSize=${inputStr}B totalTime=${totalMsStr}ms\n" +
                            "    dataTransferTime=${transferMsStr}ms jsDecodeTime=${decodeMsStr}ms jsEncodeTime=${encodeMsStr}ms\n" +
                            "    wasmHeapSize=${wasmHeapMB}MB outputImage=${outputStr}B"
                        }
                        log(Log.INFO) {
                            "Pre-process: $timePreProcess ms, WASM: $timeWasm ms, Post-process: $timePostProcess ms"
                        }
                    }

                    if (bitmap == null) {
                        throw IllegalStateException("Bitmap decoding failed (returned null).")
                    }

                    val time = System.currentTimeMillis() - start
                    log(Log.INFO) { "decodeImage() finished in $time msec" }

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
                    log(Log.ERROR) { "decodeImage() failed in $time msec. Error: ${e.message}" }
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
     * Decodes a JPEG 2000 image asynchronously using cached data with default color format (ARGB 8888).
     *
     * @param callback The callback to receive the decoded [Bitmap] or error.
     */
    fun decodeImage(callback: Callback<Bitmap>) {
        decodeImage(ColorFormat.ARGB8888, callback)
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

                    val jsonResult = ensureNotEmpty(resultFuture.get(), "JSON")

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

    private fun ensureNotEmpty(value: String?, expectedDescription: String): String {
        if (value.isNullOrBlank()) {
            throw IllegalStateException("JavaScriptEngine returned empty result - expected $expectedDescription")
        }
        return value
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
            log(Log.ERROR) { "Error closing isolate: ${e.message}" }
        } finally {
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
