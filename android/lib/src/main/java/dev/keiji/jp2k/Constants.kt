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
 * Maximum chunk size in bytes / characters for safe transfer across Android Binder transactions.
 * 256KB: Safely below the 1MB shared Binder buffer limit.
 */
const val BINDER_TRANSACTION_MAX_CHUNK_SIZE_BYTES = 256 * 1024

/**
 * Maximum addressable linear memory for WebAssembly 32-bit (4GB).
 */
const val WASM_MAX_MEMORY_BYTES = 4L * 1024 * 1024 * 1024L

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

internal const val SCRIPT_DEFINE_INPUT_CHUNKS = """
            globalThis.inputChunks = [];
            globalThis.clearInputChunks = function() {
                globalThis.inputChunks = [];
                return "$INTERNAL_RESULT_SUCCESS";
            };
            globalThis.appendInputChunk = function(chunk) {
                globalThis.inputChunks.push(chunk);
                return "$INTERNAL_RESULT_SUCCESS";
            };
            globalThis.consumeInputChunks = function() {
                const joined = globalThis.inputChunks.join('');
                globalThis.inputChunks = [];
                return joined;
            };

            globalThis.outputPayload = null;
            globalThis.clearOutput = function() {
                globalThis.outputPayload = null;
                return "$INTERNAL_RESULT_SUCCESS";
            };
            globalThis.getOutputChunk = function(offset, length) {
                if (!globalThis.outputPayload) return "";
                return globalThis.outputPayload.substring(offset, offset + length);
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

            globalThis.setDataFromChunks = function() {
                try {
                    const decodeFn = globalThis.decodePayload || globalThis.base64ToBytes;
                    const joined = globalThis.consumeInputChunks();
                    globalThis.j2kData = decodeFn(joined);
                    return "$INTERNAL_RESULT_SUCCESS";
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };
"""

internal val SCRIPT_DEFINE_DECODE_J2K = """
            globalThis.commonDecodeJ2K = function(wasmFunctionName, encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs, chunkedOutput) {
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

                    if (dataLength > maxHeapSize || dataLength > 4294967296) {
                        return JSON.stringify({ errorCode: ${Jp2kError.InputDataSize.code}, errorMessage: "Input data size (" + dataLength + " bytes) exceeds maximum allowable heap size" });
                    }

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

                    if (bmpSize > maxHeapSize || bmpSize > 4294967296) {
                        exports.free(bmpPtr);
                        exports.free(inputPtr);
                        return JSON.stringify({ errorCode: ${Jp2kError.PixelDataSize.code}, errorMessage: "Output BMP size (" + bmpSize + " bytes) exceeds maximum heap size" });
                    }

                    const bmpBuffer = new Uint8Array(exports.memory.buffer, bmpPtr, bmpSize);
                    let base64String = "";
                    let base64EncodeTime = 0;
                    if (typeof globalThis.outputMessagePort !== 'undefined' && globalThis.outputMessagePort) {
                        const bufferCopy = new Uint8Array(bmpBuffer).slice().buffer;
                        globalThis.outputMessagePort.postMessage(bufferCopy);
                    } else {
                        const encodeStart = measureTimes ? now() : 0;
                        const encodeFn = globalThis.encodePayload || globalThis.bytesToBase64;
                        base64String = encodeFn(bmpBuffer);
                        if (measureTimes) {
                            base64EncodeTime = now() - encodeStart;
                        }
                    }

                    exports.free(bmpPtr);
                    exports.free(inputPtr);

                    if (measureTimes) {
                         timeAfterPostProcess = now();
                    }

                    let result;
                    if (chunkedOutput && !(typeof globalThis.outputMessagePort !== 'undefined' && globalThis.outputMessagePort)) {
                        globalThis.outputPayload = base64String;
                        result = {
                            outputSize: base64String.length,
                            isChunked: true,
                            bmp: ""
                        };
                    } else {
                        result = {
                            bmp: base64String
                        };
                    }

                    if (measureTimes) {
                        result.inputTransferDelayMs = inputTransferDelayMs || 0;
                        result.jsFinishTimeMs = Date.now();
                        result.timeBase64Decode = base64DecodeTime || 0;
                        result.timePreProcess = timeAfterPreProcess - timeStart;
                        result.timeWasm = timeAfterDecode - timeAfterPreProcess;
                        result.timePostProcess = timeAfterPostProcess - timeAfterDecode;
                        result.timeBase64Encode = base64EncodeTime;
                        result.wasmHeapSizeBytes = (exports && exports.memory && exports.memory.buffer) ? exports.memory.buffer.byteLength : 0;
                    }

                    return JSON.stringify(result);
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };

            globalThis.internalDecodeJ2K = function(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs, chunkedOutput) {
                return globalThis.commonDecodeJ2K('decodeToBmp', encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs, chunkedOutput);
            };

            globalThis.decodeJ2K = function(dataEncodedString, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, kotlinStartTime, chunkedOutput) {
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
                        return globalThis.internalDecodeJ2K(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs, chunkedOutput);
                    } else {
                        const encodedBuffer = decodeFn(dataEncodedString);
                        return globalThis.internalDecodeJ2K(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, 0, 0, chunkedOutput);
                    }
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };

            globalThis.decodeJ2KFromChunks = function(maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, kotlinStartTime, chunkedOutput) {
                try {
                    const jsStartTime = Date.now();
                    const inputTransferDelayMs = (kotlinStartTime && kotlinStartTime > 0) ? Math.max(0, jsStartTime - kotlinStartTime) : 0;
                    const now = function() {
                        return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
                    };
                    const decodeFn = globalThis.decodePayload || globalThis.base64ToBytes;
                    const joined = globalThis.consumeInputChunks();
                    if (measureTimes) {
                        const b64Start = now();
                        const encodedBuffer = decodeFn(joined);
                        const base64DecodeTime = now() - b64Start;
                        return globalThis.internalDecodeJ2K(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs, chunkedOutput);
                    } else {
                        const encodedBuffer = decodeFn(joined);
                        return globalThis.internalDecodeJ2K(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, 0, 0, chunkedOutput);
                    }
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };

            globalThis.decodeJ2KWithCache = function(maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, kotlinStartTime, chunkedOutput) {
                if (!globalThis.j2kData) {
                    return JSON.stringify({ errorCode: ${Jp2kError.CacheDataMissing.code}, errorMessage: "No data cached" });
                }
                const jsStartTime = Date.now();
                const inputTransferDelayMs = (kotlinStartTime && kotlinStartTime > 0) ? Math.max(0, jsStartTime - kotlinStartTime) : 0;
                return globalThis.internalDecodeJ2K(globalThis.j2kData, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, 0, inputTransferDelayMs, chunkedOutput);
            };

            globalThis.internalDecodeJ2KRatio = function(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs, chunkedOutput) {
                return globalThis.commonDecodeJ2K('decodeToBmpWithRatio', encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs, chunkedOutput);
            };

            globalThis.decodeJ2KRatio = function(dataEncodedString, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, kotlinStartTime, chunkedOutput) {
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
                        return globalThis.internalDecodeJ2KRatio(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs, chunkedOutput);
                    } else {
                        const encodedBuffer = decodeFn(dataEncodedString);
                        return globalThis.internalDecodeJ2KRatio(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, 0, 0, chunkedOutput);
                    }
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };

            globalThis.decodeJ2KRatioFromChunks = function(maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, kotlinStartTime, chunkedOutput) {
                try {
                    const jsStartTime = Date.now();
                    const inputTransferDelayMs = (kotlinStartTime && kotlinStartTime > 0) ? Math.max(0, jsStartTime - kotlinStartTime) : 0;
                    const now = function() {
                        return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
                    };
                    const decodeFn = globalThis.decodePayload || globalThis.base64ToBytes;
                    const joined = globalThis.consumeInputChunks();
                    if (measureTimes) {
                        const b64Start = now();
                        const encodedBuffer = decodeFn(joined);
                        const base64DecodeTime = now() - b64Start;
                        return globalThis.internalDecodeJ2KRatio(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, base64DecodeTime, inputTransferDelayMs, chunkedOutput);
                    } else {
                        const encodedBuffer = decodeFn(joined);
                        return globalThis.internalDecodeJ2KRatio(encodedBuffer, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, 0, 0, chunkedOutput);
                    }
                } catch (e) {
                    return JSON.stringify({ errorCode: ${Jp2kError.Unknown.code}, errorMessage: e.toString() });
                }
            };

            globalThis.decodeJ2KWithCacheRatio = function(maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, kotlinStartTime, chunkedOutput) {
                if (!globalThis.j2kData) {
                    return JSON.stringify({ errorCode: ${Jp2kError.CacheDataMissing.code}, errorMessage: "No data cached" });
                }
                const jsStartTime = Date.now();
                const inputTransferDelayMs = (kotlinStartTime && kotlinStartTime > 0) ? Math.max(0, jsStartTime - kotlinStartTime) : 0;
                return globalThis.internalDecodeJ2KRatio(globalThis.j2kData, maxPixels, maxHeapSize, colorFormat, measureTimes, x0, y0, x1, y1, 0, inputTransferDelayMs, chunkedOutput);
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

                    if (dataLength > 4294967296) {
                        return JSON.stringify({ errorCode: ${Jp2kError.InputDataSize.code}, errorMessage: "Input data size exceeds maximum allowable memory" });
                    }

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

            globalThis.getSizeFromChunks = function() {
                try {
                    const decodeFn = globalThis.decodePayload || globalThis.base64ToBytes;
                    const joined = globalThis.consumeInputChunks();
                    const encodedBuffer = decodeFn(joined);
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

internal const val SCRIPT_INIT_MESSAGE_PORT = """
globalThis.bufferedBinaryInput = [];
globalThis.binaryInputResolvers = [];

globalThis.receiveBinaryMessage = function() {
    if (globalThis.bufferedBinaryInput && globalThis.bufferedBinaryInput.length > 0) {
        return Promise.resolve(globalThis.bufferedBinaryInput.shift());
    }
    return new Promise((resolve) => {
        if (!globalThis.binaryInputResolvers) {
            globalThis.binaryInputResolvers = [];
        }
        globalThis.binaryInputResolvers.push(resolve);
    });
};

globalThis.initMessagePort = async function() {
    if (typeof android !== 'undefined' && typeof android.getNamedPort === 'function') {
        try {
            const port = await android.getNamedPort('jp2k_binary_port');
            globalThis.outputMessagePort = port;
            port.onmessage = (event) => {
                const data = new Uint8Array(event.data);
                if (globalThis.binaryInputResolvers && globalThis.binaryInputResolvers.length > 0) {
                    const resolve = globalThis.binaryInputResolvers.shift();
                    resolve(data);
                } else {
                    if (!globalThis.bufferedBinaryInput) {
                        globalThis.bufferedBinaryInput = [];
                    }
                    globalThis.bufferedBinaryInput.push(data);
                }
            };
        } catch (e) {}
    }
};
"""
