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
class Jp2kDecoderAsync(context: Context, private val logLevel: Int? = null) {
    private val assetManager = context.assets
    private val sandboxFuture = JavaScriptSandbox.createConnectedInstanceAsync(context)

    // Background executor for heavy processing
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var jsIsolate: JavaScriptIsolate? = null

    private fun log(priority: Int, message: String) {
        if (logLevel != null && priority >= logLevel) {
            Log.println(priority, TAG, message)
        }
    }

    fun init(callback: Callback<Unit>) {
        val start = System.currentTimeMillis()
        backgroundExecutor.submit {
            try {
                // Wait for sandbox connection on the background thread
                val sandbox = sandboxFuture.get()
                jsIsolate = sandbox.createIsolate()

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

    fun decodeImage(j2kData: ByteArray, callback: Callback<Bitmap>) {
        backgroundExecutor.submit {
            val start = System.currentTimeMillis()
            log(Log.INFO, "Input data length: ${j2kData.size}")

            try {
                val isolate = checkNotNull(jsIsolate) { "Jp2kDecoder has not been initialized." }

                // Optimization: Use Hex string instead of joinToString(",") to reduce memory overhead and string size
                val dataHexString = j2kData.toHexString()
                val script = "globalThis.decodeJ2K('$dataHexString', $MAX_PIXELS);"

                val resultFuture = isolate.evaluateJavaScriptAsync(script)

                // Block and wait for result on background thread
                val jsonResult = resultFuture?.get() ?: throw IllegalStateException("Result Future is null")

                val root = JSONObject(jsonResult)
                if (root.has("error")) {
                    val errorMsg = root.getString("error")
                    log(Log.ERROR, "Error: $errorMsg")
                    throw IllegalStateException("Decode error occurred: $errorMsg")
                }

                val bmpHex = root.getString("bmp")
                val bmpBytes = bmpHex.hexToByteArray()

                log(Log.INFO, "Output data length: ${bmpBytes.size}")

                val bitmap = BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size)

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

    fun release() {
        if (!backgroundExecutor.isShutdown) {
            backgroundExecutor.execute {
                try {
                    jsIsolate?.close()
                    jsIsolate = null
                } catch (e: Exception) {
                    log(Log.ERROR, "Error closing isolate: ${e.message}")
                }

                try {
                    if (sandboxFuture.isDone) {
                        sandboxFuture.get().close()
                    } else {
                        sandboxFuture.cancel(true)
                    }
                } catch (e: Exception) {
                     log(Log.ERROR, "Error closing sandbox: ${e.message}")
                }
            }
            backgroundExecutor.shutdown()
        }
    }

    companion object {
        private const val TAG = "Jp2kDecoderAsync"

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

            globalThis.hexToBytes = function(hex) {
                const len = hex.length;
                if (len === 0) return new Uint8Array(0);
                const bytes = new Uint8Array(len / 2);
                for (let i = 0; i < len; i += 2) {
                    bytes[i / 2] = parseInt(hex.substring(i, i + 2), 16);
                }
                return bytes;
            };

            globalThis.decodeJ2K = function(dataHexString, maxPixels) {
                try {
                    const exports = wasmInstance.exports;

                    const encodedBuffer = globalThis.hexToBytes(dataHexString);

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
