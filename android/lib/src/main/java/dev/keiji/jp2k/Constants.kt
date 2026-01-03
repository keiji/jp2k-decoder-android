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
