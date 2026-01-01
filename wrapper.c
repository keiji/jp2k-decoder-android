#include <openjpeg.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <emscripten.h>

// エラーコードの定義
#define ERR_NONE 0
#define ERR_HEADER -1          // ヘッダ読み込み失敗（データ破損など）
#define ERR_INPUT_DATA_SIZE -2 // 入力データサイズエラー（制限オーバーまたはサイズ不足）
#define ERR_PIXEL_DATA_SIZE -3 // デコード後のピクセルデータサイズエラー（制限オーバー）
#define ERR_DECODE -4          // デコード処理失敗

#define MIN_INPUT_SIZE 12 // JP2 signature box length

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
            last_error = ERR_PIXEL_DATA_SIZE; // サイズオーバー
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

static opj_image_t* decode(uint8_t* data, uint32_t data_len, uint32_t max_pixels, uint32_t max_heap_size) {
    uint32_t max_input_size = max_heap_size / 4;

    if (data_len < MIN_INPUT_SIZE || data_len > max_input_size) {
        last_error = ERR_INPUT_DATA_SIZE;
        return NULL;
    }

    if (data_len >= 4 &&
        data[0] == 0x00 &&
        data[1] == 0x00 &&
        data[2] == 0x00 &&
        data[3] == 0x0C) {
        return decode_internal(data, data_len, OPJ_CODEC_JP2, max_pixels);
    }
    return decode_internal(data, data_len, OPJ_CODEC_J2K, max_pixels);
}

EMSCRIPTEN_KEEPALIVE
uint8_t* decodeToBmp(uint8_t* data, uint32_t data_len, uint32_t max_pixels, uint32_t max_heap_size) {
    opj_image_t* image = decode(data, data_len, max_pixels, max_heap_size);
    if (!image) {
        return NULL;
    }

    uint32_t width = image->x1 - image->x0;
    uint32_t height = image->y1 - image->y0;

    if (image->numcomps < 3) {
        opj_image_destroy(image);
        last_error = ERR_DECODE;
        return NULL;
    }

    uint32_t pixel_count = width * height;
    uint32_t bmp_header_size = 14;
    uint32_t dib_header_size = 40;
    uint32_t header_size = bmp_header_size + dib_header_size;
    uint32_t pixel_data_size = pixel_count * 4;
    uint32_t file_size = header_size + pixel_data_size;

    uint8_t* bmp_buffer = (uint8_t*)malloc(file_size);
    if (!bmp_buffer) {
        opj_image_destroy(image);
        last_error = ERR_DECODE; // Allocation failed
        return NULL;
    }

    // BMP Header
    bmp_buffer[0] = 0x42; // 'B'
    bmp_buffer[1] = 0x4D; // 'M'
    memcpy(&bmp_buffer[2], &file_size, 4);
    uint32_t reserved = 0;
    memcpy(&bmp_buffer[6], &reserved, 4);
    memcpy(&bmp_buffer[10], &header_size, 4);

    // DIB Header
    memcpy(&bmp_buffer[14], &dib_header_size, 4);
    memcpy(&bmp_buffer[18], &width, 4);
    int32_t neg_height = -(int32_t)height;
    memcpy(&bmp_buffer[22], &neg_height, 4);
    uint16_t planes = 1;
    memcpy(&bmp_buffer[26], &planes, 2);
    uint16_t bpp = 32;
    memcpy(&bmp_buffer[28], &bpp, 2);
    uint32_t compression = 0;
    memcpy(&bmp_buffer[30], &compression, 4);
    memcpy(&bmp_buffer[34], &pixel_data_size, 4);
    int32_t resolution = 0;
    memcpy(&bmp_buffer[38], &resolution, 4);
    memcpy(&bmp_buffer[42], &resolution, 4);
    uint32_t colors = 0;
    memcpy(&bmp_buffer[46], &colors, 4);
    memcpy(&bmp_buffer[50], &colors, 4);

    // Pixel Data (BGRA)
    int32_t* r_data = image->comps[0].data;
    int32_t* g_data = image->comps[1].data;
    int32_t* b_data = image->comps[2].data;

    int32_t* a_data = NULL;
    if (image->numcomps > 3) {
        // Look for alpha channel
        for (uint32_t i = 0; i < image->numcomps; i++) {
            if (image->comps[i].alpha != 0) {
                a_data = image->comps[i].data;
                break;
            }
        }
        // Fallback: if not explicitly marked, assume 4th component is alpha if 4 components exist
        if (!a_data) {
            a_data = image->comps[3].data;
        }
    }

    uint8_t* ptr = bmp_buffer + header_size;

    if (a_data) {
        for (uint32_t i = 0; i < pixel_count; i++) {
            *ptr++ = (uint8_t)b_data[i];
            *ptr++ = (uint8_t)g_data[i];
            *ptr++ = (uint8_t)r_data[i];
            *ptr++ = (uint8_t)a_data[i];
        }
    } else {
        for (uint32_t i = 0; i < pixel_count; i++) {
            *ptr++ = (uint8_t)b_data[i];
            *ptr++ = (uint8_t)g_data[i];
            *ptr++ = (uint8_t)r_data[i];
            *ptr++ = 0xFF; // Alpha
        }
    }

    opj_image_destroy(image);
    return bmp_buffer;
}
