package dev.keiji.jp2k

import androidx.javascriptengine.JavaScriptSandbox

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

/**
 * Feature flag constant for direct binary data transfer support.
 * Checks if `JavaScriptSandbox.isFeatureSupported(JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER)` returns true.
 */
internal const val JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER = JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER

/**
 * Named data key for the WASM binary when using provideNamedData.
 */
internal const val PROVIDED_WASM_DATA = "wasmBinary"

/**
 * Named data key for JPEG2000 image data when using provideNamedData.
 */
internal const val PROVIDED_J2K_DATA = "j2kData"

internal const val INTERNAL_RESULT_SUCCESS = "1"

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

internal val SCRIPT_DEFINE_SET_DATA = """
            globalThis.j2kData = null;
            globalThis.setData = function(dataEncodedString) {
                try {
                    const decodeFn = globalThis.decodePayload || globalThis.base64ToBytes;
                    globalThis.j2kData = decodeFn(dataEncodedString);
                    return "$INTERNAL_RESULT_SUCCESS";
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };
"""

internal val SCRIPT_DEFINE_DECODE_J2K = """
            globalThis.commonDecodeJ2K = function(wasmFunctionName, encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs) {
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
                    const encodeFn = globalThis.encodePayload || globalThis.bytesToBase64;
                    const base64String = encodeFn(bmpBuffer);

                    exports.free(bmpPtr);
                    exports.free(inputPtr);

                    if (measureTimes) {
                         timeAfterPostProcess = now();
                    }

                    const result = {
                        bmp: base64String
                    };

                    if (measureTimes) {
                        result.inputTransferDelayMs = inputTransferDelayMs || 0;
                        result.jsFinishTimeMs = Date.now();
                        result.timeBase64Decode = base64DecodeTime || 0;
                        result.timePreProcess = timeAfterPreProcess - timeStart;
                        result.timeWasm = timeAfterDecode - timeAfterPreProcess;
                        result.timePostProcess = timeAfterPostProcess - timeAfterDecode;
                        result.timeBase64Encode = timeAfterPostProcess - timeAfterDecode;
                        result.wasmHeapSizeBytes = (exports && exports.memory && exports.memory.buffer) ? exports.memory.buffer.byteLength : 0;
                    }

                    return JSON.stringify(result);
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };

            globalThis.internalDecodeJ2K = function(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs) {
                return globalThis.commonDecodeJ2K('decodeToBmp', encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs);
            };

            globalThis.decodeJ2K = function(dataEncodedString, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, kotlinStartTime) {
                try {
                    const jsStartTime = Date.now();
                    const inputTransferDelayMs = (kotlinStartTime && kotlinStartTime > 0) ? Math.max(0, jsStartTime - kotlinStartTime) : 0;
                    const now = function() {
                        return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
                    };
                    const decodeFn = globalThis.decodePayload || globalThis.base64ToBytes;
                    if (measureTimes) {
                        const b64Start = now();
                        const encodedBuffer = decodeFn(dataEncodedString);
                        const base64DecodeTime = now() - b64Start;
                        return globalThis.internalDecodeJ2K(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs);
                    } else {
                        const encodedBuffer = decodeFn(dataEncodedString);
                        return globalThis.internalDecodeJ2K(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, 0, 0);
                    }
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };

            globalThis.decodeJ2KWithCache = function(maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, kotlinStartTime) {
                if (!globalThis.j2kData) {
                    return JSON.stringify({ errorCode: ${Jp2kError.CacheDataMissing.code}, errorMessage: "No data cached" });
                }
                const jsStartTime = Date.now();
                const inputTransferDelayMs = (kotlinStartTime && kotlinStartTime > 0) ? Math.max(0, jsStartTime - kotlinStartTime) : 0;
                return globalThis.internalDecodeJ2K(globalThis.j2kData, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, 0, inputTransferDelayMs);
            };

            globalThis.internalDecodeJ2KRatio = function(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs) {
                return globalThis.commonDecodeJ2K('decodeToBmpWithRatio', encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs);
            };

            globalThis.decodeJ2KRatio = function(dataEncodedString, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, kotlinStartTime) {
                try {
                    const jsStartTime = Date.now();
                    const inputTransferDelayMs = (kotlinStartTime && kotlinStartTime > 0) ? Math.max(0, jsStartTime - kotlinStartTime) : 0;
                    const now = function() {
                        return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
                    };
                    const decodeFn = globalThis.decodePayload || globalThis.base64ToBytes;
                    if (measureTimes) {
                        const b64Start = now();
                        const encodedBuffer = decodeFn(dataEncodedString);
                        const base64DecodeTime = now() - b64Start;
                        return globalThis.internalDecodeJ2KRatio(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs);
                    } else {
                        const encodedBuffer = decodeFn(dataEncodedString);
                        return globalThis.internalDecodeJ2KRatio(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, 0, 0);
                    }
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };

            globalThis.decodeJ2KWithCacheRatio = function(maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, kotlinStartTime) {
                if (!globalThis.j2kData) {
                    return JSON.stringify({ errorCode: ${Jp2kError.CacheDataMissing.code}, errorMessage: "No data cached" });
                }
                const jsStartTime = Date.now();
                const inputTransferDelayMs = (kotlinStartTime && kotlinStartTime > 0) ? Math.max(0, jsStartTime - kotlinStartTime) : 0;
                return globalThis.internalDecodeJ2KRatio(globalThis.j2kData, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, 0, inputTransferDelayMs);
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

            globalThis.getSize = function(dataEncodedString) {
                try {
                    const decodeFn = globalThis.decodePayload || globalThis.base64ToBytes;
                    const encodedBuffer = decodeFn(dataEncodedString);
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

internal const val SCRIPT_TRANSFER_FROM_PROVIDED_NAMED_DATA = """
globalThis.transferFromProvidedNamedData = async function(key) {
    const provided = await android.consumeNamedDataAsArrayBuffer(key);
    if (provided === undefined || provided === null) return null;
    return provided.byteLength > 0 ? new Uint8Array(provided) : new Uint8Array(0);
};
"""
