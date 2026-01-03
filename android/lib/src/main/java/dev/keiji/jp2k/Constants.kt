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

internal const val SCRIPT_BYTES_HEX_CONVERTER = """
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
"""

internal const val SCRIPT_DEFINE_DECODE_J2K = """
            globalThis.decodeJ2K = function(dataHexString, maxPixels, maxHeapSize, colorFormat, measureTimes) {
                const now = function() {
                    return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
                };

                let timeStart, timeAfterPreProcess, timeAfterDecode, timeAfterPostProcess;
                try {
                    if (measureTimes) {
                         timeStart = now();
                    }

                    const exports = wasmInstance.exports;

                    const encodedBuffer = globalThis.hexToBytes(dataHexString);

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
                    const hexString = globalThis.bytesToHex(bmpBuffer);

                    exports.free(bmpPtr);
                    exports.free(inputPtr);

                    if (measureTimes) {
                         timeAfterPostProcess = now();
                    }

                    const result = {
                        bmp: hexString
                    };

                    if (measureTimes) {
                        result.timePreProcess = timeAfterPreProcess - timeStart;
                        result.timeWasm = timeAfterDecode - timeAfterPreProcess;
                        result.timePostProcess = timeAfterPostProcess - timeAfterDecode;
                    }

                    return JSON.stringify(result);
                } catch (e) {
                    return JSON.stringify({ error: e.toString() });
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
