package dev.keiji.jp2k

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Jp2kDecoder(context: Context) {
    private val assetManager = context.assets
    private val sandboxFuture = JavaScriptSandbox.createConnectedInstanceAsync(context)
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private var jsIsolate: JavaScriptIsolate? = null

    suspend fun init() {
        suspendCancellableCoroutine { continuation ->
            sandboxFuture.addListener({
                try {
                    val sandbox = sandboxFuture.get()
                    jsIsolate = sandbox.createIsolate()
                    continuation.resume(Unit)
                } catch (exception: Exception) {
                    continuation.resumeWithException(exception)
                }
            }, mainExecutor)
        }
        loadWasm()
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

    suspend fun decodeImage(j2kData: ByteArray): Bitmap {
        val jsIsolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }

        // JP2のマジックナンバーチェック (00 00 00 0C 6A 50 20 20)
        val isJp2 = j2kData.size > 4 &&
                j2kData[0] == 0x00.toByte() &&
                j2kData[1] == 0x00.toByte() &&
                j2kData[2] == 0x00.toByte() &&
                j2kData[3] == 0x0C.toByte()

        val dataArrayString = j2kData.joinToString(",")
        val script = "globalThis.decodeJ2K([$dataArrayString], $isJp2, $MAX_PIXELS);"

        val resultFuture = jsIsolate.evaluateJavaScriptAsync(script)

        val structureJson = suspendCancellableCoroutine { continuation ->
            resultFuture?.addListener({
                val jsonResult = resultFuture.get()
                continuation.resume(jsonResult)
            }, mainExecutor)
        }

        val root = JSONObject(structureJson)
        if (root.has("error")) {
            throw IllegalStateException("Decode error occurred: ${root.getString("errorCode")}")
        }

        val width = root.getInt("width")
        val height = root.getInt("height")
        val pixelsArray = root.getJSONArray("pixels")

        // JSの[R,G,B,A]の並びを、Android用のIntArray(0xAARRGGBB)に変換
        val pixelCount = width * height

        val colors = IntArray(pixelCount)

        for (i in 0 until pixelCount) {
            val r = pixelsArray.getInt(i * 4)
            val g = pixelsArray.getInt(i * 4 + 1)
            val b = pixelsArray.getInt(i * 4 + 2)
            val a = pixelsArray.getInt(i * 4 + 3)
            colors[i] = Color.argb(a, r, g, b)
        }

        // Bitmapを作成してピクセルを設定
        return createBitmap(width, height).also {
            it.setPixels(colors, 0, width, 0, 0, width, height)
        }
    }

    companion object {
        private const val TAG = "Jp2kDecoder"

        private const val ASSET_PATH_WASM = "openjpeg_core.wasm"
        private const val MAX_PIXELS = 16000000

        private const val SCRIPT_IMPORT_OBJECT = """
        const wasiSnapshotPreview = {
            // 環境変数の数とサイズ
            environ_sizes_get: (p_environ_count, p_environ_buf_size) => {
                const view = new DataView(wasmInstance.exports.memory.buffer);
                view.setUint32(p_environ_count, 0, true);
                view.setUint32(p_environ_buf_size, 0, true);
                return 0;
            },
            // 環境変数の実データを書き込む
            environ_get: (p_environ, p_environ_buf) => 0,
    
            // 標準出力・エラー出力
            fd_write: (fd, iovs, iovs_len, p_nwritten) => {
                    const view = new DataView(wasmInstance.exports.memory.buffer);
                    let total = 0;
                    let msg = "";
                    for (let i = 0; i < iovs_len; i++) {
                        const ptr = view.getUint32(iovs + i * 8, true);
                        const len = view.getUint32(iovs + i * 8 + 4, true);
                        const strBytes = new Uint8Array(wasmInstance.exports.memory.buffer, ptr, len);
                        msg += new TextDecoder().decode(strBytes);
                        total += len;
                    }
                    view.setUint32(p_nwritten, total, true);
                    console.log("WASM_LOG: " + msg);
                    return 0;
                },
            fd_close: (fd) => 0,
            fd_seek: (fd, offset_low, offset_high, whence, p_new_offset) => 0,
            
            // プログラム終了
            proc_exit: (code) => {
                console.log("WASM exited with code: " + code);
            }
        };
        const env = {
            emscripten_notify_memory_growth: (index) => {
                // DO NOTHING
            }
        };
        const importObject = {
            wasi_snapshot_preview1: wasiSnapshotPreview,
            env: env,
        };
        """

        private const val SCRIPT_DEFINE_DECODE_J2K = """
            globalThis.decodeJ2K = function(dataArrayString, isJp2, maxPixels) {
                try {
                    const exports = wasmInstance.exports;

                    const encodedBuffer = new Uint8Array(dataArrayString);

                    // 渡されたデータの長さをチェック
                    const dataLength = encodedBuffer.length;
                    if (dataLength === 0) return JSON.stringify({ error: "Input array is empty" });
            
                    const inputPtr = exports.malloc(dataLength);
                    const heap = new Uint8Array(exports.memory.buffer);
                    
                    heap.set(encodedBuffer, inputPtr);
                    
                    const imagePtr = isJp2 ? exports.decodeJp2(inputPtr, encodedBuffer.length, maxPixels) 
                               : exports.decodeRaw(inputPtr, encodedBuffer.length, maxPixels);
                    if (imagePtr === 0) {
                        const errCode = exports.getLastError();
                        exports.free(inputPtr);
            
                        return JSON.stringify({ errorCode: errorCode });
                    }
                
                    const heapU32 = new Uint32Array(exports.memory.buffer);
                    const width = heapU32[imagePtr / 4 + 2];
                    const height = heapU32[imagePtr / 4 + 3];
                    const compsPtr = heapU32[imagePtr / 4 + 6];
                
                    const pixelCount = width * height;
                    const rgba = new Uint8ClampedArray(pixelCount * 4);
                    
                    const compSize = 52;
                    const rDataPtr = heapU32[(compsPtr + 0 * compSize + 44) / 4];
                    const gDataPtr = heapU32[(compsPtr + 1 * compSize + 44) / 4];
                    const bDataPtr = heapU32[(compsPtr + 2 * compSize + 44) / 4];
                
                    const rData = new Int32Array(exports.memory.buffer, rDataPtr, pixelCount);
                    const gData = new Int32Array(exports.memory.buffer, gDataPtr, pixelCount);
                    const bData = new Int32Array(exports.memory.buffer, bDataPtr, pixelCount);
                
                    for (let i = 0; i < pixelCount; i++) {
                        rgba[i * 4 + 0] = rData[i];
                        rgba[i * 4 + 1] = gData[i];
                        rgba[i * 4 + 2] = bData[i];
                        rgba[i * 4 + 3] = 255;
                    }
                
                    exports.opj_image_destroy(imagePtr);
                    exports.free(inputPtr);
                
                    return JSON.stringify({
                        width: width,
                        height: height,
                        pixels: Array.from(rgba)
                    });
                } catch (e) {
                    return JSON.stringify({ error: e.toString() });
                }
            };            
        """
    }
}
