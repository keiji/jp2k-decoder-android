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

internal const val INTERNAL_RESULT_SUCCESS = "1"

internal const val SCRIPT_IMPORT_OBJECT = """
const wasiSnapshotPreview = {
    // 環境変数の数とサイズ
    environ_sizes_get: (p_environ_count, p_environ_buf_size) => {
        const view = new DataView(wasmInstance.exports.memory.buffer);
        view.setUint32(p_environ_count, 0, true);
        view.setUint32(p_environ_buf_size, 0, true);
        return 0; // SUCCESS
    },
    // 環境変数の内容
    environ_get: (p_environ, p_environ_buf) => {
        return 0; // SUCCESS
    },
    // クロック・時刻
    clock_time_get: (id, precision, p_time) => {
        const view = new DataView(wasmInstance.exports.memory.buffer);
        // FIXME とりあえず現在時刻のモック(ゼロ)を返す
        view.setBigUint64(p_time, 0n, true);
        return 0; // SUCCESS
    },
    // プロセスの終了
    proc_exit: (rval) => {
        throw new Error(`WASI exit: ${'$'}{rval}`);
    },
    // ファイルディスクリプタへの書き込み (stdout/stderrのモック)
    fd_write: (fd, iovs_ptr, iovs_len, nwritten_ptr) => {
        const memory = new Uint8Array(wasmInstance.exports.memory.buffer);
        const view = new DataView(wasmInstance.exports.memory.buffer);

        let bytesWritten = 0;

        for (let i = 0; i < iovs_len; i++) {
            const ptr = view.getUint32(iovs_ptr + i * 8, true);
            const len = view.getUint32(iovs_ptr + i * 8 + 4, true);

            // FIXME とりあえず出力は無視する
            // const str = new TextDecoder("utf-8").decode(new Uint8Array(memory.buffer, ptr, len));
            // console.log(`fd_write(${'$'}{fd}): ${'$'}{str}`);

            bytesWritten += len;
        }

        view.setUint32(nwritten_ptr, bytesWritten, true);
        return 0; // SUCCESS
    },
    fd_close: (fd) => { return 0; },
    fd_seek: (fd, offset, whence, newoffset_ptr) => { return 0; }
};

const importObject = {
    wasi_snapshot_preview1: wasiSnapshotPreview,
    env: {
        emscripten_notify_memory_growth: (index) => {
            // No-op
        }
    }
};
"""

internal const val SCRIPT_BYTES_TO_BASE64_CONVERTER = """
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
"""

internal val SCRIPT_DEFINE_SET_DATA = """
            globalThis.j2kData = null;
            globalThis.setData = async function(dataId) {
                try {
                    const buffer = await android.consumeNamedDataAsArrayBuffer(dataId);
                    globalThis.j2kData = new Uint8Array(buffer);
                    return "$INTERNAL_RESULT_SUCCESS";
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };
"""

internal val SCRIPT_DEFINE_DECODE_J2K = """
            globalThis.commonDecodeJ2K = function(wasmFunctionName, encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1) {
                const now = function() {
                    return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
                };

                let timeStart, timeAfterPreProcess, timeAfterDecode, timeAfterPostProcess;
                try {
                    if (measureTimes) {
                         timeStart = now();
                    }

                    const exports = wasmInstance.exports;

                    const dataLength = encodedBuffer.length;
                    if (dataLength === 0) return JSON.stringify({ errorCode: -1 });

                    const inputPtr = exports.malloc(dataLength);
                    const heap = new Uint8Array(exports.memory.buffer);

                    heap.set(encodedBuffer, inputPtr);

                    if (measureTimes) {
                         timeAfterPreProcess = now();
                    }

                    // Call the specified WASM function
                    const bmpPtr = exports[wasmFunctionName](inputPtr, encodedBuffer.length, maxPixels, maxHeapSize, colorFormat, x0, y0, x1, y1);

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

            globalThis.internalDecodeJ2K = function(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1) {
                return globalThis.commonDecodeJ2K('decodeToBmp', encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1);
            };

            globalThis.decodeJ2K = async function(dataId, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1) {
                try {
                    const buffer = await android.consumeNamedDataAsArrayBuffer(dataId);
                    const encodedBuffer = new Uint8Array(buffer);
                    return globalThis.internalDecodeJ2K(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1);
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };

            globalThis.decodeJ2KWithCache = function(maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1) {
                if (!globalThis.j2kData) {
                    return JSON.stringify({ errorCode: ${Jp2kError.CacheDataMissing.code}, errorMessage: "No data cached" });
                }
                return globalThis.internalDecodeJ2K(globalThis.j2kData, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1);
            };

            globalThis.internalDecodeJ2KRatio = function(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1) {
                return globalThis.commonDecodeJ2K('decodeToBmpWithRatio', encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1);
            };

            globalThis.decodeJ2KRatio = async function(dataId, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1) {
                try {
                    const buffer = await android.consumeNamedDataAsArrayBuffer(dataId);
                    const encodedBuffer = new Uint8Array(buffer);
                    return globalThis.internalDecodeJ2KRatio(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1);
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };

            globalThis.decodeJ2KWithCacheRatio = function(maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1) {
                if (!globalThis.j2kData) {
                    return JSON.stringify({ errorCode: ${Jp2kError.CacheDataMissing.code}, errorMessage: "No data cached" });
                }
                return globalThis.internalDecodeJ2KRatio(globalThis.j2kData, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1);
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
            globalThis.internalGetSize = function(encodedBuffer) {
                try {
                    const exports = wasmInstance.exports;
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

            globalThis.getSize = async function(dataId) {
                try {
                    const buffer = await android.consumeNamedDataAsArrayBuffer(dataId);
                    const encodedBuffer = new Uint8Array(buffer);
                    return globalThis.internalGetSize(encodedBuffer);
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };

            globalThis.getSizeWithCache = function() {
                if (!globalThis.j2kData) {
                    return JSON.stringify({ errorCode: ${Jp2kError.CacheDataMissing.code}, errorMessage: "No data cached" });
                }
                return globalThis.internalGetSize(globalThis.j2kData);
            };
        """
