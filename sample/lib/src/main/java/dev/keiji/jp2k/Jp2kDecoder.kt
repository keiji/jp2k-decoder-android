package dev.keiji.jp2k

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
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
            throw IllegalStateException("Decode error occurred: ${root.getString("error")}")
        }

        val bmpBase64 = root.getString("bmp")
        val bmpBytes = Base64.decode(bmpBase64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size)
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
            globalThis.bytesToBase64 = function(bytes) {
                const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
                let output = '';
                let len = bytes.length;
                let i = 0;
                while (i < len) {
                    let a = bytes[i++];
                    let b = bytes[i++];
                    let c = bytes[i++];

                    let enc1 = a >> 2;
                    let enc2 = ((a & 3) << 4) | (b >> 4);
                    let enc3 = ((b & 15) << 2) | (c >> 6);
                    let enc4 = c & 63;

                    if (isNaN(b)) {
                        enc3 = enc4 = 64;
                    } else if (isNaN(c)) {
                        enc4 = 64;
                    }

                    output += chars.charAt(enc1) + chars.charAt(enc2) + chars.charAt(enc3) + chars.charAt(enc4);
                }
                return output;
            };

            globalThis.createBmp = function(width, height, rData, gData, bData) {
                const pixelCount = width * height;
                const bmpHeaderSize = 14;
                const dibHeaderSize = 40;
                const headerSize = bmpHeaderSize + dibHeaderSize;
                const pixelDataSize = pixelCount * 4;
                const fileSize = headerSize + pixelDataSize;

                const bmpBuffer = new Uint8Array(fileSize);
                const view = new DataView(bmpBuffer.buffer);

                // BMP Header
                view.setUint8(0, 0x42); // 'B'
                view.setUint8(1, 0x4D); // 'M'
                view.setUint32(2, fileSize, true);
                view.setUint16(6, 0, true);
                view.setUint16(8, 0, true);
                view.setUint32(10, headerSize, true);

                // DIB Header
                view.setUint32(14, dibHeaderSize, true);
                view.setInt32(18, width, true);
                view.setInt32(22, -height, true); // Top-down
                view.setUint16(26, 1, true);
                view.setUint16(28, 32, true); // 32-bit color
                view.setUint32(30, 0, true);
                view.setUint32(34, pixelDataSize, true);
                view.setInt32(38, 0, true);
                view.setInt32(42, 0, true);
                view.setUint32(46, 0, true);
                view.setUint32(50, 0, true);

                // Pixel Data (BGRA)
                let ptr = headerSize;
                for (let i = 0; i < pixelCount; i++) {
                    bmpBuffer[ptr++] = bData[i];
                    bmpBuffer[ptr++] = gData[i];
                    bmpBuffer[ptr++] = rData[i];
                    bmpBuffer[ptr++] = 255;
                }
                return bmpBuffer;
            };

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
                        const errorCode = exports.getLastError();
                        exports.free(inputPtr);
            
                        return JSON.stringify({ error: "OpenJPEG error code: " + errorCode });
                    }
                
                    const heapU32 = new Uint32Array(exports.memory.buffer);
                    const width = heapU32[imagePtr / 4 + 2];
                    const height = heapU32[imagePtr / 4 + 3];
                    const compsPtr = heapU32[imagePtr / 4 + 6];
                
                    const pixelCount = width * height;
                    
                    const compSize = 52;
                    const rDataPtr = heapU32[(compsPtr + 0 * compSize + 44) / 4];
                    const gDataPtr = heapU32[(compsPtr + 1 * compSize + 44) / 4];
                    const bDataPtr = heapU32[(compsPtr + 2 * compSize + 44) / 4];
                
                    const rData = new Int32Array(exports.memory.buffer, rDataPtr, pixelCount);
                    const gData = new Int32Array(exports.memory.buffer, gDataPtr, pixelCount);
                    const bData = new Int32Array(exports.memory.buffer, bDataPtr, pixelCount);
                
                    const bmpBuffer = globalThis.createBmp(width, height, rData, gData, bData);

                    exports.opj_image_destroy(imagePtr);
                    exports.free(inputPtr);
                
                    return JSON.stringify({
                        bmp: globalThis.bytesToBase64(bmpBuffer)
                    });
                } catch (e) {
                    return JSON.stringify({ error: e.toString() });
                }
            };            
        """
    }
}
