#include <openjpeg.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <emscripten.h>

// エラーコードの定義
#define ERR_NONE 0
#define ERR_HEADER -1    // ヘッダ読み込み失敗（データ破損など）
#define ERR_TOO_LARGE -2 // サイズ制限オーバー
#define ERR_DECODE -3    // デコード処理失敗

int last_error = ERR_NONE;

// 最新のエラーコードを取得する関数
EMSCRIPTEN_KEEPALIVE
int getLastError() {
    return last_error;
}

typedef struct {
    OPJ_BYTE* data;
    OPJ_SIZE_T size;
    OPJ_SIZE_T offset;
} opj_buffer_info_t;

static OPJ_SIZE_T opj_read_from_buffer(void* p_buffer, OPJ_SIZE_T p_nb_bytes, void* p_user_data) {
    opj_buffer_info_t* p_info = (opj_buffer_info_t*)p_user_data;
    OPJ_SIZE_T l_nb_read = p_nb_bytes;
    if (p_info->offset >= p_info->size) return (OPJ_SIZE_T)-1;
    if (p_info->offset + p_nb_bytes > p_info->size) l_nb_read = p_info->size - p_info->offset;
    memcpy(p_buffer, p_info->data + p_info->offset, l_nb_read);
    p_info->offset += l_nb_read;
    return l_nb_read;
}

// 内部共通デコード処理（ガード付き）
static opj_image_t* decode_internal(uint8_t* data, uint32_t data_len, OPJ_CODEC_FORMAT format, uint32_t max_pixels) {
    last_error = ERR_NONE; // リセット
    
    opj_buffer_info_t buffer_info = {data, data_len, 0};
    
    opj_codec_t* l_codec = opj_create_decompress(format);
    opj_dparameters_t l_params;
    opj_set_default_decoder_parameters(&l_params);
    opj_setup_decoder(l_codec, &l_params);

    opj_stream_t* l_stream = opj_stream_default_create(OPJ_TRUE);
    opj_stream_set_read_function(l_stream, opj_read_from_buffer);
    opj_stream_set_user_data(l_stream, &buffer_info, NULL);
    opj_stream_set_user_data_length(l_stream, data_len);

    opj_image_t* l_image = NULL;
    if (!opj_read_header(l_stream, l_codec, &l_image)) {
        last_error = ERR_HEADER; // ヘッダで失敗
    } else {
        uint32_t width = l_image->x1 - l_image->x0;
        uint32_t height = l_image->y1 - l_image->y0;
        
        if (max_pixels > 0 && ((uint64_t)width * height) > max_pixels) {
            last_error = ERR_TOO_LARGE; // サイズオーバー
            opj_image_destroy(l_image);
            l_image = NULL;
        } else if (!opj_decode(l_codec, l_stream, l_image)) {
            last_error = ERR_DECODE; // デコード自体で失敗
            opj_image_destroy(l_image);
            l_image = NULL;
        }
    }
    opj_stream_destroy(l_stream);
    opj_destroy_codec(l_codec);
    
    return l_image;
}

EMSCRIPTEN_KEEPALIVE
opj_image_t* decodeRaw(uint8_t* data, uint32_t data_len, uint32_t max_pixels) {
    return decode_internal(data, data_len, OPJ_CODEC_J2K, max_pixels);
}

EMSCRIPTEN_KEEPALIVE
opj_image_t* decodeJp2(uint8_t* data, uint32_t data_len, uint32_t max_pixels) {
    return decode_internal(data, data_len, OPJ_CODEC_JP2, max_pixels);
}
