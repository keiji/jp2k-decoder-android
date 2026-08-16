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
import com.google.common.util.concurrent.ListenableFuture
import dev.keiji.jp2k.datachannel.Base64DataChannel
import dev.keiji.jp2k.datachannel.JSDataChannel
import dev.keiji.jp2k.datachannel.createDataChannel
import dev.keiji.jp2k.datachannel.escapeJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
            isEvaluateWithoutTransactionLimitSupported =
                JavaScriptEngineEnvironment.isFeatureSupported(sandbox, JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)
            dataChannel = createDataChannel(sandbox, config.preferDirectBinaryTransfer)
            log(Log.INFO) { "DataChannel: ${dataChannel.name}" }

            val isolate = Jp2kSandbox.createIsolate(
                sandbox = sandbox,
                maxHeapSizeBytes = config.maxHeapSizeBytes,
                maxEvaluationReturnSizeBytes = config.maxEvaluationReturnSizeBytes,
            ).also { isolate ->
                Jp2kSandbox.setupConsoleCallback(isolate, sandbox, mainExecutor, TAG)
            }

            dataChannel.setupIsolate(isolate, mainExecutor)

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
            log(Log.INFO) { "init() finished in $time msec" }
        } catch (e: Exception) {
            if (_state != State.Released && _state != State.Releasing) {
                _state = State.Uninitialized
            }
            val time = System.currentTimeMillis() - start
            log(Log.ERROR) { "init() failed in $time msec. Error: ${e.message}" }
            throw e
        }
    }

    private suspend fun loadWasm(isolate: JavaScriptIsolate, assetManager: AssetManager) {
        withContext(coroutineDispatcher) {
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
                $SCRIPT_DEFINE_INPUT_CHUNKS_LOCAL
                $SCRIPT_DEFINE_SET_DATA_LOCAL
                $SCRIPT_IMPORT_OBJECT_LOCAL

                (async () => {
                    ${dataChannel.jsInitExpression}
                    return "$INTERNAL_RESULT_SUCCESS";
                })();
            """.trimIndent()

            val setupResultFuture = isolate.evaluateJavaScriptAsync(setupScript)
            try {
                val setupResult = setupResultFuture.await()
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

                    $SCRIPT_DEFINE_DECODE_J2K_LOCAL
                    $SCRIPT_DEFINE_GET_SIZE_LOCAL

                    return "$INTERNAL_RESULT_SUCCESS";
                })();
            """.trimIndent()

            val resultFuture = isolate.evaluateJavaScriptAsync(instantiateScript)
            try {
                val result = resultFuture.await()
                if (result != INTERNAL_RESULT_SUCCESS) {
                    ensureNotEmpty(result, "Success indicator")
                    throw IllegalStateException("WASM instantiation failed.")
                }
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    private fun validateInputSize(size: Int) {
        val maxAllowable = minOf(config.maxHeapSizeBytes, config.wasmMaxMemoryBytes)
        if (size.toLong() > maxAllowable) {
            throw Jp2kException(
                Jp2kError.InputDataSize,
                "Input data size ($size bytes) exceeds maximum allowable size ($maxAllowable bytes)",
            )
        }
    }

    private suspend fun transferInputInChunks(isolate: JavaScriptIsolate, encoded: String) {
        isolate.evaluateJavaScriptAsync("globalThis.clearInputChunks();").await()
        var offset = 0
        while (offset < encoded.length) {
            val end = minOf(offset + config.binderTransactionMaxChunkSizeBytes, encoded.length)
            val chunk = encoded.substring(offset, end).escapeJs()
            isolate.evaluateJavaScriptAsync("globalThis.appendInputChunk('$chunk');").await()
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
     * @throws Exception If precaching fails.
     */
    suspend fun precache(j2kData: ByteArray) = mutex.withLock {
        if (_state == State.Released || _state == State.Releasing) {
            throw CancellationException("Decoder was released.")
        }
        if (_state != State.Initialized) {
            throw IllegalStateException("Cannot precache while in state: $_state")
        }
        validateInputSize(j2kData.size)
        _state = State.Processing

        try {
            val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }
            withContext(coroutineDispatcher) {
                log(Log.INFO) { "DataChannel: ${dataChannel.name}" }
                log(Log.INFO) { "Input binary length: ${j2kData.size}" }

                val result = if (!isEvaluateWithoutTransactionLimitSupported && dataChannel.isStringMediated) {
                    val encoded = dataChannel.encodePayload(j2kData)
                    transferInputInChunks(isolate, encoded)
                    isolate.evaluateJavaScriptAsync("globalThis.setDataFromChunks();").await()
                } else {
                    val script = dataChannel.getJ2KExpression(isolate, j2kData)
                    log(Log.INFO) { "J2K expression: $script" }
                    isolate.evaluateJavaScriptAsync(script).await()
                }

                if (result != INTERNAL_RESULT_SUCCESS) {
                    ensureNotEmpty(result, "Success indicator or JSON error")

                    val root = JSONObject(result)
                    if (root.has("errorCode")) {
                        val errorCode = root.getInt("errorCode")
                        val error = Jp2kError.fromInt(errorCode)
                        val errorMessage =
                            if (root.has("errorMessage")) root.getString("errorMessage") else null
                        log(Log.ERROR) { "Error: $error, Message: $errorMessage" }
                        throw Jp2kException(error, errorMessage)
                    }
                    throw IllegalStateException("Failed to set data: $result")
                }
            }
        } catch (e: Exception) {
            log(Log.ERROR) { "precache() failed. Error: ${e.message}" }
            throw e
        } finally {
            restoreStateAfterDecode()
        }
    }

    /**
     * Retrieves the size of the JPEG 2000 image without fully decoding it.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @return The [Size] of the image.
     */
    suspend fun getSize(j2kData: ByteArray): Size {
        validateInputSize(j2kData.size)
        logInputDataInfo(j2kData)
        val encoded = dataChannel.encodePayload(j2kData)
        logEncodedInputInfo(encoded)

        return executeGetSize { isolate ->
            if (!isEvaluateWithoutTransactionLimitSupported && dataChannel.isStringMediated) {
                transferInputInChunks(isolate, encoded)
                isolate.evaluateJavaScriptAsync("globalThis.getSizeFromChunks();").await()
            } else {
                isolate.evaluateJavaScriptAsync("globalThis.getSize('$encoded');").await()
            }
        }
    }

    /**
     * Retrieves the size of the JPEG 2000 image using cached data.
     *
     * @return The [Size] of the image.
     */
    suspend fun getSize(): Size {
        return executeGetSize { isolate ->
            isolate.evaluateJavaScriptAsync("globalThis.getSizeWithCache();").await()
        }
    }

    private suspend fun executeGetSize(
        evaluate: suspend (JavaScriptIsolate) -> String,
    ): Size = mutex.withLock {
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
    ): Bitmap = decodeImage(j2kData, 0, 0, 0, 0, colorFormat)

    /**
     * Decodes a specific region of a JPEG 2000 image.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @param left The left coordinate of the region.
     * @param top The top coordinate of the region.
     * @param right The right coordinate of the region.
     * @param bottom The bottom coordinate of the region.
     * @param colorFormat The desired output color format. Defaults to [ColorFormat.ARGB8888].
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        j2kData: ByteArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
    ): Bitmap {
        if (j2kData.size < MIN_INPUT_SIZE) {
            throw IllegalArgumentException("Input data is too short")
        }
        validateInputSize(j2kData.size)
        logInputDataInfo(j2kData)

        val measureTimes = config.logLevel != null
        val encoded = dataChannel.encodePayload(j2kData)
        logEncodedInputInfo(encoded)

        val kotlinStartTime = System.currentTimeMillis()
        val chunkedOutput = !isEvaluateWithoutTransactionLimitSupported

        return executeDecodeImage(colorFormat, j2kData.size.toLong()) { isolate ->
            if (!isEvaluateWithoutTransactionLimitSupported && dataChannel.isStringMediated) {
                transferInputInChunks(isolate, encoded)
                isolate.evaluateJavaScriptAsync(
                    "globalThis.decodeJ2KFromChunks(${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"
                ).await()
            } else {
                val script = "globalThis.decodeJ2K('$encoded', ${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"
                isolate.evaluateJavaScriptAsync(script).await()
            }
        }
    }

    /**
     * Decodes a specific region of a JPEG 2000 image.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @param region The region to decode.
     * @param colorFormat The desired output color format. Defaults to [ColorFormat.ARGB8888].
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        j2kData: ByteArray,
        region: Rect,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
    ): Bitmap {
        return decodeImage(j2kData, region.left, region.top, region.right, region.bottom, colorFormat)
    }

    /**
     * Decodes a specific region of a JPEG 2000 image.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @param region The region to decode.
     * @param colorFormat The desired output color format. Defaults to [ColorFormat.ARGB8888].
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        j2kData: ByteArray,
        region: RectF,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
    ): Bitmap {
        return decodeImage(j2kData, region.left, region.top, region.right, region.bottom, colorFormat)
    }

    /**
     * Decodes a specific region of a JPEG 2000 image.
     *
     * @param j2kData The raw byte array of the JPEG 2000 image.
     * @param left The left coordinate ratio (0.0 - 1.0).
     * @param top The top coordinate ratio (0.0 - 1.0).
     * @param right The right coordinate ratio (0.0 - 1.0).
     * @param bottom The bottom coordinate ratio (0.0 - 1.0).
     * @param colorFormat The desired output color format. Defaults to [ColorFormat.ARGB8888].
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        j2kData: ByteArray,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
    ): Bitmap {
        if (j2kData.size < MIN_INPUT_SIZE) {
            throw IllegalArgumentException("Input data is too short")
        }
        validateInputSize(j2kData.size)
        validateRatio(left, top, right, bottom)

        logInputDataInfo(j2kData)

        val measureTimes = config.logLevel != null
        val encoded = dataChannel.encodePayload(j2kData)
        logEncodedInputInfo(encoded)

        val kotlinStartTime = System.currentTimeMillis()
        val chunkedOutput = !isEvaluateWithoutTransactionLimitSupported

        return executeDecodeImage(colorFormat, j2kData.size.toLong()) { isolate ->
            if (!isEvaluateWithoutTransactionLimitSupported && dataChannel.isStringMediated) {
                transferInputInChunks(isolate, encoded)
                isolate.evaluateJavaScriptAsync(
                    "globalThis.decodeJ2KRatioFromChunks(${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"
                ).await()
            } else {
                val script = "globalThis.decodeJ2KRatio('$encoded', ${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"
                isolate.evaluateJavaScriptAsync(script).await()
            }
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
    ): Bitmap = decodeImage(0, 0, 0, 0, colorFormat)

    /**
     * Decodes a specific region of a JPEG 2000 image using cached data.
     *
     * @param left The left coordinate of the region.
     * @param top The top coordinate of the region.
     * @param right The right coordinate of the region.
     * @param bottom The bottom coordinate of the region.
     * @param colorFormat The desired output color format. Defaults to [ColorFormat.ARGB8888].
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
    ): Bitmap {
        val measureTimes = config.logLevel != null
        val kotlinStartTime = System.currentTimeMillis()
        val chunkedOutput = !isEvaluateWithoutTransactionLimitSupported
        val script =
            "globalThis.decodeJ2KWithCache(${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"

        return executeDecodeImage(colorFormat) { isolate ->
            isolate.evaluateJavaScriptAsync(script).await()
        }
    }

    /**
     * Decodes a specific region of a JPEG 2000 image using cached data.
     *
     * @param region The region to decode.
     * @param colorFormat The desired output color format. Defaults to [ColorFormat.ARGB8888].
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        region: Rect,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
    ): Bitmap {
        return decodeImage(region.left, region.top, region.right, region.bottom, colorFormat)
    }

    /**
     * Decodes a specific region of a JPEG 2000 image using cached data.
     *
     * @param region The region to decode.
     * @param colorFormat The desired output color format. Defaults to [ColorFormat.ARGB8888].
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        region: RectF,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
    ): Bitmap {
        return decodeImage(region.left, region.top, region.right, region.bottom, colorFormat)
    }

    /**
     * Decodes a specific region of a JPEG 2000 image using cached data.
     *
     * @param left The left coordinate ratio (0.0 - 1.0).
     * @param top The top coordinate ratio (0.0 - 1.0).
     * @param right The right coordinate ratio (0.0 - 1.0).
     * @param bottom The bottom coordinate ratio (0.0 - 1.0).
     * @param colorFormat The desired output color format. Defaults to [ColorFormat.ARGB8888].
     * @return The decoded [Bitmap].
     */
    suspend fun decodeImage(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        colorFormat: ColorFormat = ColorFormat.ARGB8888,
    ): Bitmap {
        validateRatio(left, top, right, bottom)

        val measureTimes = config.logLevel != null
        val kotlinStartTime = System.currentTimeMillis()
        val chunkedOutput = !isEvaluateWithoutTransactionLimitSupported
        val script =
            "globalThis.decodeJ2KWithCacheRatio(${config.maxPixels}, ${config.maxHeapSizeBytes}, ${colorFormat.id}, $measureTimes, $left, $top, $right, $bottom, $kotlinStartTime, $chunkedOutput);"

        return executeDecodeImage(colorFormat) { isolate ->
            isolate.evaluateJavaScriptAsync(script).await()
        }
    }

    private fun validateRatio(left: Float, top: Float, right: Float, bottom: Float) {
        if (left < 0.0f || left > 1.0f || top < 0.0f || top > 1.0f ||
            right < 0.0f || right > 1.0f || bottom < 0.0f || bottom > 1.0f
        ) {
            throw IllegalArgumentException("Ratio must be 0.0 - 1.0")
        }
    }

    private suspend fun executeDecodeImage(
        colorFormat: ColorFormat,
        inputSize: Long = 0L,
        evaluate: suspend (JavaScriptIsolate) -> String,
    ): Bitmap = mutex.withLock {
        if (_state == State.Released || _state == State.Releasing) {
            throw CancellationException("Decoder was released.")
        }
        if (_state != State.Initialized) {
            throw IllegalStateException("Cannot decodeImage while in state: $_state")
        }
        _state = State.Processing

        dataChannel.prepareForDecode()

        val start = System.currentTimeMillis()

        return try {
            val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }

            val bitmap = withContext(coroutineDispatcher) {
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
                        val length = minOf(config.binderTransactionMaxChunkSizeBytes, outputSize - offset)
                        val chunk = isolate.evaluateJavaScriptAsync("globalThis.getOutputChunk($offset, $length);").await()
                        sb.append(chunk)
                        offset += length
                    }
                    isolate.evaluateJavaScriptAsync("globalThis.clearOutput();").await()
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

                val bmp = BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size, options)
                    ?: throw IllegalStateException("Bitmap decoding failed (returned null).")
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

                bmp
            }

            val time = System.currentTimeMillis() - start
            log(Log.INFO) { "decodeImage() finished in $time msec" }

            restoreStateAfterDecode()

            if (_state == State.Released || _state == State.Releasing) {
                throw CancellationException("Decoder was released.")
            }
            bitmap

        } catch (e: Exception) {
            val time = System.currentTimeMillis() - start
            log(Log.ERROR) { "decodeImage() failed in $time msec. Error: ${e.message}" }
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

    private fun ensureNotEmpty(value: String?, expectedDescription: String): String {
        if (value.isNullOrBlank()) {
            throw IllegalStateException("JavaScriptEngine returned empty result - expected $expectedDescription")
        }
        return value
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
                val jsonResult = ensureNotEmpty(resultFuture.await(), "JSON")

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
            log(Log.ERROR) { "Error closing isolate: ${e.message}" }
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

        private val SCRIPT_DEFINE_INPUT_CHUNKS_LOCAL = SCRIPT_DEFINE_INPUT_CHUNKS
        private val SCRIPT_DEFINE_SET_DATA_LOCAL = SCRIPT_DEFINE_SET_DATA
        private const val SCRIPT_IMPORT_OBJECT_LOCAL = SCRIPT_IMPORT_OBJECT
        private val SCRIPT_DEFINE_DECODE_J2K_LOCAL = SCRIPT_DEFINE_DECODE_J2K
        private val SCRIPT_DEFINE_GET_SIZE_LOCAL = SCRIPT_DEFINE_GET_SIZE
    }
}
