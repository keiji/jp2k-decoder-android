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

// Color Format
#define COLOR_FORMAT_RGB565 565
#define COLOR_FORMAT_ARGB8888 8888

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

static opj_image_t* decode_opj(uint8_t* data, uint32_t data_len, uint32_t max_pixels, uint32_t max_heap_size, int color_format) {
    uint32_t divider = (color_format == COLOR_FORMAT_RGB565) ? 2 : 4;
    uint32_t max_input_size = max_heap_size / divider;

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

static int32_t* get_alpha_component(opj_image_t* image) {
    if (image->numcomps <= 3) {
        return NULL;
    }

    // Look for alpha channel
    for (uint32_t i = 0; i < image->numcomps; i++) {
        if (image->comps[i].alpha != 0) {
            return image->comps[i].data;
        }
    }

    // Fallback: if not explicitly marked, assume 4th component is alpha if 4 components exist
    return image->comps[3].data;
}

static void write_pixels_argb8888(uint8_t* dest, int32_t* r, int32_t* g, int32_t* b, int32_t* a, uint32_t count) {
    // ARGB8888: R G B A (Byte order)
    if (a != NULL) {
        for (uint32_t i = 0; i < count; i++) {
            *dest++ = (uint8_t)r[i];
            *dest++ = (uint8_t)g[i];
            *dest++ = (uint8_t)b[i];
            *dest++ = (uint8_t)a[i];
        }
    } else {
        for (uint32_t i = 0; i < count; i++) {
            *dest++ = (uint8_t)r[i];
            *dest++ = (uint8_t)g[i];
            *dest++ = (uint8_t)b[i];
            *dest++ = 0xFF;
        }
    }
}

static void write_pixels_rgb565(uint8_t* dest, int32_t* r, int32_t* g, int32_t* b, uint32_t count) {
    // RGB565: 16-bit packed pixel.
    // Format: RRRRRGGG GGGBBBBB (5-6-5 bits)
    // R: 5 bits, G: 6 bits, B: 5 bits.
    // Stored in Little Endian (Low Byte, High Byte).

    uint16_t* ptr = (uint16_t*)dest;

    for (uint32_t i = 0; i < count; i++) {
        // Scale 8-bit components to 5 or 6 bits
        uint16_t red = ((uint16_t)r[i] >> 3) & 0x1F;   // 8 -> 5 bits
        uint16_t green = ((uint16_t)g[i] >> 2) & 0x3F; // 8 -> 6 bits
        uint16_t blue = ((uint16_t)b[i] >> 3) & 0x1F;  // 8 -> 5 bits

        uint16_t color = (red << 11) | (green << 5) | blue;
        *ptr++ = color; // Stores as LE automatically
    }
}

EMSCRIPTEN_KEEPALIVE
uint8_t* decode(uint8_t* data, uint32_t data_len, uint32_t max_pixels, uint32_t max_heap_size, int color_format) {
    opj_image_t* image = decode_opj(data, data_len, max_pixels, max_heap_size, color_format);
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

    uint32_t bytes_per_pixel = (color_format == COLOR_FORMAT_RGB565) ? 2 : 4;
    uint32_t pixel_data_size = pixel_count * bytes_per_pixel;

    // Allocate buffer: [Width(4)][Height(4)][Pixels...]
    uint32_t buffer_size = 8 + pixel_data_size;

    uint8_t* result_buffer = (uint8_t*)malloc(buffer_size);
    if (!result_buffer) {
        opj_image_destroy(image);
        last_error = ERR_DECODE; // Allocation failed
        return NULL;
    }

    // Write header
    memcpy(result_buffer, &width, 4);
    memcpy(result_buffer + 4, &height, 4);

    // Pixel Data
    int32_t* r_data = image->comps[0].data;
    int32_t* g_data = image->comps[1].data;
    int32_t* b_data = image->comps[2].data;
    int32_t* a_data = get_alpha_component(image);

    uint8_t* ptr = result_buffer + 8;

    if (color_format == COLOR_FORMAT_RGB565) {
        write_pixels_rgb565(ptr, r_data, g_data, b_data, pixel_count);
    } else {
        // ARGB8888
        write_pixels_argb8888(ptr, r_data, g_data, b_data, a_data, pixel_count);
    }

    opj_image_destroy(image);
    return result_buffer;
}
