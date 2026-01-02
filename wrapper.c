#include <openjpeg.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <emscripten.h>

// Error Codes
#define ERR_NONE 0
#define ERR_HEADER -1
#define ERR_INPUT_DATA_SIZE -2
#define ERR_PIXEL_DATA_SIZE -3
#define ERR_DECODE -4

#define MIN_INPUT_SIZE 12

// Color Formats
#define COLOR_FORMAT_RGB565 565
#define COLOR_FORMAT_ARGB8888 8888

int last_error = ERR_NONE;

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

static opj_image_t* decode_internal(uint8_t* data, uint32_t data_len, OPJ_CODEC_FORMAT format, uint32_t max_pixels) {
    last_error = ERR_NONE;
    
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
        last_error = ERR_HEADER;
    } else {
        uint32_t width = l_image->x1 - l_image->x0;
        uint32_t height = l_image->y1 - l_image->y0;
        
        if (max_pixels > 0 && ((uint64_t)width * height) > max_pixels) {
            last_error = ERR_PIXEL_DATA_SIZE;
            opj_image_destroy(l_image);
            l_image = NULL;
        } else if (!opj_decode(l_codec, l_stream, l_image)) {
            last_error = ERR_DECODE;
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
    if (image->numcomps <= 3) return NULL;
    for (uint32_t i = 0; i < image->numcomps; i++) {
        if (image->comps[i].alpha != 0) return image->comps[i].data;
    }
    return image->comps[3].data;
}

static void write_headers_argb8888(uint8_t* buffer, uint32_t file_size, uint32_t width, uint32_t height) {
    uint32_t bmp_header_size = 14;
    uint32_t dib_header_size = 40;
    uint32_t offset = bmp_header_size + dib_header_size;

    // BMP Header
    buffer[0] = 0x42; // 'B'
    buffer[1] = 0x4D; // 'M'
    memcpy(&buffer[2], &file_size, 4);
    uint32_t reserved = 0;
    memcpy(&buffer[6], &reserved, 4);
    memcpy(&buffer[10], &offset, 4);

    // DIB Header (BITMAPINFOHEADER)
    memcpy(&buffer[14], &dib_header_size, 4);
    memcpy(&buffer[18], &width, 4);
    int32_t neg_height = -(int32_t)height; // Top-down
    memcpy(&buffer[22], &neg_height, 4);
    uint16_t planes = 1;
    memcpy(&buffer[26], &planes, 2);
    uint16_t bpp = 32;
    memcpy(&buffer[28], &bpp, 2);
    uint32_t compression = 0; // BI_RGB
    memcpy(&buffer[30], &compression, 4);
    uint32_t image_size = 0; // Can be 0 for BI_RGB
    memcpy(&buffer[34], &image_size, 4);
    int32_t resolution = 0;
    memcpy(&buffer[38], &resolution, 4);
    memcpy(&buffer[42], &resolution, 4);
    uint32_t colors = 0;
    memcpy(&buffer[46], &colors, 4);
    memcpy(&buffer[50], &colors, 4);
}

static void write_headers_rgb565(uint8_t* buffer, uint32_t file_size, uint32_t width, uint32_t height) {
    uint32_t bmp_header_size = 14;
    uint32_t dib_header_size = 40;
    uint32_t mask_size = 12; // 3 * 4 bytes for bitfields
    uint32_t offset = bmp_header_size + dib_header_size + mask_size;

    // BMP Header
    buffer[0] = 0x42;
    buffer[1] = 0x4D;
    memcpy(&buffer[2], &file_size, 4);
    uint32_t reserved = 0;
    memcpy(&buffer[6], &reserved, 4);
    memcpy(&buffer[10], &offset, 4);

    // DIB Header
    memcpy(&buffer[14], &dib_header_size, 4);
    memcpy(&buffer[18], &width, 4);
    int32_t neg_height = -(int32_t)height;
    memcpy(&buffer[22], &neg_height, 4);
    uint16_t planes = 1;
    memcpy(&buffer[26], &planes, 2);
    uint16_t bpp = 16;
    memcpy(&buffer[28], &bpp, 2);
    uint32_t compression = 3; // BI_BITFIELDS
    memcpy(&buffer[30], &compression, 4);
    uint32_t image_size = 0;
    memcpy(&buffer[34], &image_size, 4);
    int32_t resolution = 0;
    memcpy(&buffer[38], &resolution, 4);
    memcpy(&buffer[42], &resolution, 4);
    uint32_t colors = 0;
    memcpy(&buffer[46], &colors, 4);
    memcpy(&buffer[50], &colors, 4);

    // Color Masks (Red, Green, Blue)
    // 565 format: R(5) G(6) B(5)
    // Masks are usually written as DWORDs in R, G, B order
    uint32_t r_mask = 0xF800;
    uint32_t g_mask = 0x07E0;
    uint32_t b_mask = 0x001F;
    memcpy(&buffer[54], &r_mask, 4);
    memcpy(&buffer[58], &g_mask, 4);
    memcpy(&buffer[62], &b_mask, 4);
}

EMSCRIPTEN_KEEPALIVE
uint8_t* decodeToBmp(uint8_t* data, uint32_t data_len, uint32_t max_pixels, uint32_t max_heap_size, int color_format) {
    opj_image_t* image = decode_opj(data, data_len, max_pixels, max_heap_size, color_format);
    if (!image) return NULL;

    uint32_t width = image->x1 - image->x0;
    uint32_t height = image->y1 - image->y0;

    if (image->numcomps < 3) {
        opj_image_destroy(image);
        last_error = ERR_DECODE;
        return NULL;
    }

    int32_t* r_data = image->comps[0].data;
    int32_t* g_data = image->comps[1].data;
    int32_t* b_data = image->comps[2].data;
    int32_t* a_data = get_alpha_component(image);

    uint8_t* bmp_buffer = NULL;

    if (color_format == COLOR_FORMAT_RGB565) {
        // RGB565: 2 bytes per pixel. Rows padded to 4 bytes.
        uint32_t row_bytes = (width * 2 + 3) & ~3;
        uint32_t pixel_data_size = row_bytes * height;
        uint32_t header_size = 14 + 40 + 12; // Header + DIB + Masks
        uint32_t file_size = header_size + pixel_data_size;

        bmp_buffer = (uint8_t*)malloc(file_size);
        if (!bmp_buffer) {
            opj_image_destroy(image);
            last_error = ERR_DECODE;
            return NULL;
        }

        write_headers_rgb565(bmp_buffer, file_size, width, height);

        uint8_t* ptr = bmp_buffer + header_size;
        for (uint32_t y = 0; y < height; y++) {
            uint16_t* row_ptr = (uint16_t*)ptr;
            for (uint32_t x = 0; x < width; x++) {
                uint32_t idx = y * width + x;
                uint16_t r = ((uint16_t)r_data[idx] >> 3) & 0x1F;
                uint16_t g = ((uint16_t)g_data[idx] >> 2) & 0x3F;
                uint16_t b = ((uint16_t)b_data[idx] >> 3) & 0x1F;
                row_ptr[x] = (r << 11) | (g << 5) | b;
            }
            ptr += row_bytes;
        }

    } else {
        // ARGB8888: 4 bytes per pixel. Rows always aligned to 4.
        uint32_t row_bytes = width * 4;
        uint32_t pixel_data_size = row_bytes * height;
        uint32_t header_size = 14 + 40;
        uint32_t file_size = header_size + pixel_data_size;

        bmp_buffer = (uint8_t*)malloc(file_size);
        if (!bmp_buffer) {
            opj_image_destroy(image);
            last_error = ERR_DECODE;
            return NULL;
        }

        write_headers_argb8888(bmp_buffer, file_size, width, height);

        uint8_t* ptr = bmp_buffer + header_size;
        if (a_data) {
            for (uint32_t i = 0; i < width * height; i++) {
                *ptr++ = (uint8_t)b_data[i];
                *ptr++ = (uint8_t)g_data[i];
                *ptr++ = (uint8_t)r_data[i];
                *ptr++ = (uint8_t)a_data[i];
            }
        } else {
            for (uint32_t i = 0; i < width * height; i++) {
                *ptr++ = (uint8_t)b_data[i];
                *ptr++ = (uint8_t)g_data[i];
                *ptr++ = (uint8_t)r_data[i];
                *ptr++ = 0xFF;
            }
        }
    }

    opj_image_destroy(image);
    return bmp_buffer;
}
