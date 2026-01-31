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
#define ERR_DECODER_SETUP -5
#define ERR_REGION_OUT_OF_BOUNDS -6
#define ERR_MEMORY -7

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

static OPJ_CODEC_FORMAT get_codec_format(uint8_t* data, uint32_t data_len) {
    if (data_len >= 4 &&
        data[0] == 0x00 &&
        data[1] == 0x00 &&
        data[2] == 0x00 &&
        data[3] == 0x0C) {
        return OPJ_CODEC_JP2;
    }
    return OPJ_CODEC_J2K;
}

static opj_codec_t* create_decoder(OPJ_CODEC_FORMAT format) {
    opj_codec_t* l_codec = opj_create_decompress(format);
    if (!l_codec) return NULL;

    opj_dparameters_t l_params;
    opj_set_default_decoder_parameters(&l_params);
    if (!opj_setup_decoder(l_codec, &l_params)) {
        opj_destroy_codec(l_codec);
        return NULL;
    }
    return l_codec;
}

static opj_stream_t* create_mem_stream(opj_buffer_info_t* buffer_info, uint32_t data_len) {
    opj_stream_t* l_stream = opj_stream_default_create(OPJ_TRUE);
    opj_stream_set_read_function(l_stream, opj_read_from_buffer);
    opj_stream_set_user_data(l_stream, buffer_info, NULL);
    opj_stream_set_user_data_length(l_stream, data_len);
    return l_stream;
}

static opj_image_t* decode_internal(uint8_t* data, uint32_t data_len, OPJ_CODEC_FORMAT format, uint32_t max_pixels, double x0, double y0, double x1, double y1, int use_ratio) {
    last_error = ERR_NONE;

    opj_buffer_info_t buffer_info = {data, data_len, 0};

    opj_codec_t* l_codec = create_decoder(format);
    if (!l_codec) {
        last_error = ERR_DECODER_SETUP;
        return NULL;
    }

    opj_stream_t* l_stream = create_mem_stream(&buffer_info, data_len);

    opj_image_t* l_image = NULL;
    if (!opj_read_header(l_stream, l_codec, &l_image)) {
        last_error = ERR_HEADER;
    } else {
        uint32_t width = l_image->x1 - l_image->x0;
        uint32_t height = l_image->y1 - l_image->y0;

        uint32_t ux0, uy0, ux1, uy1;
        if (use_ratio) {
            ux0 = (uint32_t)(width * x0);
            uy0 = (uint32_t)(height * y0);
            ux1 = (uint32_t)(width * x1);
            uy1 = (uint32_t)(height * y1);
            if (ux1 > width) ux1 = width;
            if (uy1 > height) uy1 = height;
        } else {
            ux0 = (uint32_t)x0;
            uy0 = (uint32_t)y0;
            ux1 = (uint32_t)x1;
            uy1 = (uint32_t)y1;
        }

        // Check bounds if partial decoding is requested (x1 > 0 or y1 > 0)
        // If x1 and y1 are 0, we assume full decode (no crop)
        // Note: For ratio, x1/y1 will be > 0 if valid ratio passed.
        // We need to handle 0,0,0,0 ratio as full decode too?
        // Usually ratio 0.0 to 1.0 means 0 to width.
        // If user passes 0,0,0,0 as ratio, it means empty region?
        // But the previous API used 0,0,0,0 as 'full decode'.
        // If use_ratio is true, we expect valid ratios.
        // However, if we want full decode via ratio, we'd pass 0.0, 0.0, 1.0, 1.0.
        // Let's stick to: if use_ratio is false, 0,0,0,0 means full.
        // If use_ratio is true, 0,0,0,0 -> ux0=0, uy0=0, ux1=0, uy1=0 -> is_partial=0 -> full decode.
        // So it works out.

        int is_partial = (ux1 != 0 || uy1 != 0);
        int bounds_ok = 1;

        if (is_partial) {
             if (ux0 < l_image->x0 || uy0 < l_image->y0 || ux1 > l_image->x1 || uy1 > l_image->y1 || ux0 >= ux1 || uy0 >= uy1) {
                 bounds_ok = 0;
             } else {
                 if (!opj_set_decode_area(l_codec, l_image, ux0, uy0, ux1, uy1)) {
                     // Should not happen if bounds are ok, but safety check
                     bounds_ok = 0;
                 }
             }
        }
        
        if (!bounds_ok) {
            last_error = ERR_REGION_OUT_OF_BOUNDS;
            opj_image_destroy(l_image);
            l_image = NULL;
        } else if (max_pixels > 0 && ((uint64_t)width * height) > max_pixels) {
            uint32_t output_width = is_partial ? (ux1 - ux0) : width;
            uint32_t output_height = is_partial ? (uy1 - uy0) : height;

            if (((uint64_t)output_width * output_height) > max_pixels) {
                last_error = ERR_PIXEL_DATA_SIZE;
                opj_image_destroy(l_image);
                l_image = NULL;
            } else if (!opj_decode(l_codec, l_stream, l_image)) {
                last_error = ERR_DECODE;
                opj_image_destroy(l_image);
                l_image = NULL;
            }
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

static opj_image_t* decode_opj_common(uint8_t* data, uint32_t data_len, uint32_t max_pixels, uint32_t max_heap_size, int color_format, double x0, double y0, double x1, double y1, int use_ratio) {
    uint32_t divider = (color_format == COLOR_FORMAT_RGB565) ? 2 : 4;
    uint32_t max_input_size = max_heap_size / divider;

    if (!data || data_len < MIN_INPUT_SIZE || data_len > max_input_size) {
        last_error = ERR_INPUT_DATA_SIZE;
        return NULL;
    }

    OPJ_CODEC_FORMAT format = get_codec_format(data, data_len);
    return decode_internal(data, data_len, format, max_pixels, x0, y0, x1, y1, use_ratio);
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

static uint8_t* convert_image_to_bmp(opj_image_t* image, int color_format) {
    uint32_t width = image->x1 - image->x0;
    uint32_t height = image->y1 - image->y0;

    if (image->numcomps < 1) {
        last_error = ERR_DECODE;
        return NULL;
    }

    int32_t* r_data = NULL;
    int32_t* g_data = NULL;
    int32_t* b_data = NULL;
    int32_t* a_data = NULL;

    if (image->numcomps == 1) {
        r_data = image->comps[0].data;
        g_data = image->comps[0].data;
        b_data = image->comps[0].data;
    } else if (image->numcomps == 2) {
        r_data = image->comps[0].data;
        g_data = image->comps[0].data;
        b_data = image->comps[0].data;
        if (image->comps[1].alpha != 0) {
            a_data = image->comps[1].data;
        }
    } else {
        r_data = image->comps[0].data;
        g_data = image->comps[1].data;
        b_data = image->comps[2].data;
        a_data = get_alpha_component(image);
    }

    uint8_t* bmp_buffer = NULL;

    if (color_format == COLOR_FORMAT_RGB565) {
        // RGB565: 2 bytes per pixel. Rows padded to 4 bytes.
        uint32_t row_bytes = (width * 2 + 3) & ~3;
        uint32_t pixel_data_size = row_bytes * height;
        uint32_t header_size = 14 + 40 + 12; // Header + DIB + Masks
        uint32_t file_size = header_size + pixel_data_size;

        bmp_buffer = (uint8_t*)malloc(file_size);
        if (!bmp_buffer) {
            last_error = ERR_MEMORY;
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
            last_error = ERR_MEMORY;
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
    return bmp_buffer;
}

EMSCRIPTEN_KEEPALIVE
uint8_t* decodeToBmp(uint8_t* data, uint32_t data_len, uint32_t max_pixels, uint32_t max_heap_size, int color_format, uint32_t x0, uint32_t y0, uint32_t x1, uint32_t y1) {
    opj_image_t* image = decode_opj_common(data, data_len, max_pixels, max_heap_size, color_format, (double)x0, (double)y0, (double)x1, (double)y1, 0);
    if (!image) return NULL;

    uint8_t* bmp_buffer = convert_image_to_bmp(image, color_format);

    opj_image_destroy(image);
    return bmp_buffer;
}

EMSCRIPTEN_KEEPALIVE
uint8_t* decodeToBmpWithRatio(uint8_t* data, uint32_t data_len, uint32_t max_pixels, uint32_t max_heap_size, int color_format, double x0, double y0, double x1, double y1) {
    opj_image_t* image = decode_opj_common(data, data_len, max_pixels, max_heap_size, color_format, x0, y0, x1, y1, 1);
    if (!image) return NULL;

    uint8_t* bmp_buffer = convert_image_to_bmp(image, color_format);

    opj_image_destroy(image);
    return bmp_buffer;
}

EMSCRIPTEN_KEEPALIVE
uint32_t* getSize(uint8_t* data, uint32_t data_len) {
    last_error = ERR_NONE;
    if (!data || data_len < MIN_INPUT_SIZE) {
        last_error = ERR_INPUT_DATA_SIZE;
        return NULL;
    }

    opj_buffer_info_t buffer_info = {data, data_len, 0};

    OPJ_CODEC_FORMAT format = get_codec_format(data, data_len);

    opj_codec_t* l_codec = create_decoder(format);
    if (!l_codec) {
        last_error = ERR_DECODER_SETUP;
        return NULL;
    }

    opj_stream_t* l_stream = create_mem_stream(&buffer_info, data_len);

    opj_image_t* l_image = NULL;
    uint32_t* result = NULL;

    if (!opj_read_header(l_stream, l_codec, &l_image)) {
        last_error = ERR_HEADER;
    } else {
        uint32_t width = l_image->x1 - l_image->x0;
        uint32_t height = l_image->y1 - l_image->y0;

        result = (uint32_t*)malloc(2 * sizeof(uint32_t));
        if (result) {
            result[0] = width;
            result[1] = height;
        } else {
            last_error = ERR_MEMORY;
        }
        opj_image_destroy(l_image);
    }

    opj_stream_destroy(l_stream);
    opj_destroy_codec(l_codec);

    return result;
}
