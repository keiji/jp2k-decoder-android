package dev.keiji.jp2k

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

        val dataArrayString = j2kData.joinToString(",")
        val script = "globalThis.decodeJ2K([$dataArrayString], $MAX_PIXELS);"

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

        val bmpHex = root.getString("bmp")
        val bmpBytes = hexStringToByteArray(bmpHex)
        return BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size)
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                    Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
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

            globalThis.decodeJ2K = function(dataArrayString, maxPixels) {
                try {
                    const exports = wasmInstance.exports;

                    const encodedBuffer = new Uint8Array(dataArrayString);

                    // 渡されたデータの長さをチェック
                    const dataLength = encodedBuffer.length;
                    if (dataLength === 0) return JSON.stringify({ error: "Input array is empty" });
            
                    const inputPtr = exports.malloc(dataLength);
                    const heap = new Uint8Array(exports.memory.buffer);
                    
                    heap.set(encodedBuffer, inputPtr);
                    
                    // Call the new C function decodeToBmp
                    const bmpPtr = exports.decodeToBmp(inputPtr, encodedBuffer.length, maxPixels);

                    if (bmpPtr === 0) {
                        const errorCode = exports.getLastError();
                        exports.free(inputPtr);
                        return JSON.stringify({ error: "OpenJPEG error code: " + errorCode });
                    }
                    
                    // The BMP file size is stored at offset 2 (4 bytes, little endian) in the BMP header
                    const view = new DataView(exports.memory.buffer);
                    const bmpSize = view.getUint32(bmpPtr + 2, true);

                    // Create a Uint8Array view of the BMP data
                    const bmpBuffer = new Uint8Array(exports.memory.buffer, bmpPtr, bmpSize);

                    const hexString = globalThis.bytesToHex(bmpBuffer);

                    // Free the BMP buffer allocated in C
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
