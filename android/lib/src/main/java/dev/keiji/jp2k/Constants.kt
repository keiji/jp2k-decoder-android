package dev.keiji.jp2k

/**
 * Default maximum heap size in bytes.
 *
 * 512MB: Sufficient to decode large high-resolution images (e.g. 4000x3000) which may require significant internal buffer space.
 */
const val DEFAULT_MAX_HEAP_SIZE_BYTES = 512L * 1024 * 1024

/**
 * Default maximum evaluation return size in bytes.
 *
 * 256MB: Sufficient to return the decoded pixel data (e.g. 4000x3000 * 4 bytes/pixel ~= 48MB) plus overhead as a Hex string or byte array.
 */
const val DEFAULT_MAX_EVALUATION_RETURN_SIZE_BYTES = 256 * 1024 * 1024

/**
 * Default maximum number of pixels allowed.
 */
const val DEFAULT_MAX_PIXELS = 16000000

internal const val SCRIPT_IMPORT_OBJECT = """
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
        // iovsから書き込みデータの合計サイズを計算します。
        // これを行わず0バイト書き込みとして返すと、呼び出し元（libc）が未完了とみなして
        // 無限ループ（再試行）に陥る可能性があるため、データは捨てつつ「全て書き込んだ」ように振る舞います。
        for (let i = 0; i < iovs_len; i++) {
            const len = view.getUint32(iovs + i * 8 + 4, true);
            total += len;
        }
        view.setUint32(p_nwritten, total, true);
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

internal const val SCRIPT_BYTES_BASE64_CONVERTER = """
            globalThis.bytesToBase64 = function(bytes) {
                const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                let output = "";
                for (let i = 0; i < bytes.length; i += 3) {
                    const b1 = bytes[i];
                    const b2 = i + 1 < bytes.length ? bytes[i + 1] : 0;
                    const b3 = i + 2 < bytes.length ? bytes[i + 2] : 0;

                    const e1 = b1 >> 2;
                    const e2 = ((b1 & 3) << 4) | (b2 >> 4);
                    const e3 = ((b2 & 15) << 2) | (b3 >> 6);
                    const e4 = b3 & 63;

                    output += chars.charAt(e1) + chars.charAt(e2);
                    if (i + 1 < bytes.length) {
                        output += chars.charAt(e3);
                    } else {
                        output += "=";
                    }
                    if (i + 2 < bytes.length) {
                        output += chars.charAt(e4);
                    } else {
                        output += "=";
                    }
                }
                return output;
            };

            globalThis.base64ToBytes = function(base64) {
                const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                const lookup = new Uint8Array(256);
                for (let i = 0; i < chars.length; i++) {
                    lookup[chars.charCodeAt(i)] = i;
                }

                let bufferLength = base64.length * 0.75;
                let len = base64.length;
                let i, p = 0, encoded1, encoded2, encoded3, encoded4;

                if (base64[base64.length - 1] === "=") {
                    bufferLength--;
                    if (base64[base64.length - 2] === "=") {
                        bufferLength--;
                    }
                }

                const bytes = new Uint8Array(bufferLength);

                for (i = 0; i < len; i += 4) {
                    encoded1 = lookup[base64.charCodeAt(i)];
                    encoded2 = lookup[base64.charCodeAt(i + 1)];
                    encoded3 = lookup[base64.charCodeAt(i + 2)];
                    encoded4 = lookup[base64.charCodeAt(i + 3)];

                    bytes[p++] = (encoded1 << 2) | (encoded2 >> 4);
                    if (p < bufferLength) bytes[p++] = ((encoded2 & 15) << 4) | (encoded3 >> 2);
                    if (p < bufferLength) bytes[p++] = ((encoded3 & 3) << 6) | (encoded4 & 63);
                }

                return bytes;
            };
"""

internal val SCRIPT_DEFINE_DECODE_J2K = """
            globalThis.decodeJ2K = function(dataBase64String, maxPixels, maxHeapSize, colorFormat, measureTimes) {
                const now = function() {
                    return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
                };

                let timeStart, timeAfterPreProcess, timeAfterDecode, timeAfterPostProcess;
                try {
                    if (measureTimes) {
                         timeStart = now();
                    }

                    const exports = wasmInstance.exports;

                    const encodedBuffer = globalThis.base64ToBytes(dataBase64String);

                    const dataLength = encodedBuffer.length;
                    if (dataLength === 0) return JSON.stringify({ errorCode: -1 });

                    const inputPtr = exports.malloc(dataLength);
                    const heap = new Uint8Array(exports.memory.buffer);

                    heap.set(encodedBuffer, inputPtr);

                    if (measureTimes) {
                         timeAfterPreProcess = now();
                    }

                    // Call decodeToBmp
                    const bmpPtr = exports.decodeToBmp(inputPtr, encodedBuffer.length, maxPixels, maxHeapSize, colorFormat);

                    if (measureTimes) {
                         timeAfterDecode = now();
                    }

                    if (bmpPtr === 0) {
                        const errorCode = exports.getLastError();
                        exports.free(inputPtr);
                        return JSON.stringify({ errorCode: errorCode });
                    }

                    const view = new DataView(exports.memory.buffer);
                    const bmpSize = view.getUint32(bmpPtr + 2, true);

                    const bmpBuffer = new Uint8Array(exports.memory.buffer, bmpPtr, bmpSize);
                    const base64String = globalThis.bytesToBase64(bmpBuffer);

                    exports.free(bmpPtr);
                    exports.free(inputPtr);

                    if (measureTimes) {
                         timeAfterPostProcess = now();
                    }

                    const result = {
                        bmp: base64String
                    };

                    if (measureTimes) {
                        result.timePreProcess = timeAfterPreProcess - timeStart;
                        result.timeWasm = timeAfterDecode - timeAfterPreProcess;
                        result.timePostProcess = timeAfterPostProcess - timeAfterDecode;
                    }

                    return JSON.stringify(result);
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
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

internal val SCRIPT_DEFINE_GET_SIZE = """
            globalThis.getSize = function(dataBase64String) {
                try {
                    const exports = wasmInstance.exports;
                    const encodedBuffer = globalThis.base64ToBytes(dataBase64String);
                    const dataLength = encodedBuffer.length;

                    if (dataLength === 0) return JSON.stringify({ errorCode: -1 });

                    const inputPtr = exports.malloc(dataLength);
                    const heap = new Uint8Array(exports.memory.buffer);

                    heap.set(encodedBuffer, inputPtr);

                    // Call getSize
                    const resultPtr = exports.getSize(inputPtr, dataLength);

                    if (resultPtr === 0) {
                        const errorCode = exports.getLastError();
                        exports.free(inputPtr);
                        return JSON.stringify({ errorCode: errorCode });
                    }

                    const view = new DataView(exports.memory.buffer);
                    const width = view.getUint32(resultPtr, true);
                    const height = view.getUint32(resultPtr + 4, true);

                    exports.free(resultPtr);
                    exports.free(inputPtr);

                    return JSON.stringify({
                        width: width,
                        height: height
                    });
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };
        """
