#include <openjpeg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Stubs for functions used in wrapper.c but not needed for testing convert_to_bmp

int stub_should_header_succeed = 0;
uint32_t stub_width = 0;
uint32_t stub_height = 0;

int stub_should_decompress_create_succeed = 1;
int stub_should_setup_succeed = 1;
int stub_should_decode_succeed = 0;
int stub_should_set_decode_area_succeed = 1;

opj_codec_t* opj_create_decompress(OPJ_CODEC_FORMAT format) {
    if (stub_should_decompress_create_succeed) {
        // Return a non-NULL dummy pointer
        return (opj_codec_t*)malloc(1);
    }
    return NULL;
}
void opj_set_default_decoder_parameters(opj_dparameters_t *parameters) {}
OPJ_BOOL opj_setup_decoder(opj_codec_t *p_codec, opj_dparameters_t *parameters) {
    if (stub_should_setup_succeed) return OPJ_TRUE;
    return OPJ_FALSE;
}
opj_stream_t* opj_stream_default_create(OPJ_BOOL p_is_input) { return (opj_stream_t*)malloc(1); }
void opj_stream_set_read_function(opj_stream_t* p_stream, opj_stream_read_fn p_function) {}
void opj_stream_set_user_data(opj_stream_t* p_stream, void * p_data, opj_stream_free_user_data_fn p_function) {}
void opj_stream_set_user_data_length(opj_stream_t* p_stream, OPJ_UINT64 data_length) {}
OPJ_BOOL opj_read_header(opj_stream_t *p_stream, opj_codec_t *p_codec, opj_image_t **p_image) {
    if (stub_should_header_succeed) {
        *p_image = (opj_image_t*)calloc(1, sizeof(opj_image_t));
        (*p_image)->x0 = 0;
        (*p_image)->y0 = 0;
        (*p_image)->x1 = stub_width;
        (*p_image)->y1 = stub_height;
        (*p_image)->numcomps = 4;
        (*p_image)->comps = (opj_image_comp_t*)calloc(4, sizeof(opj_image_comp_t));
        return OPJ_TRUE;
    }
    return OPJ_FALSE;
}
OPJ_BOOL opj_set_decode_area(opj_codec_t *p_codec, opj_image_t* p_image, OPJ_INT32 p_start_x, OPJ_INT32 p_start_y, OPJ_INT32 p_end_x, OPJ_INT32 p_end_y) {
    if (stub_should_set_decode_area_succeed) return OPJ_TRUE;
    return OPJ_FALSE;
}
void opj_image_destroy(opj_image_t *image) {
    if (image) {
        if (image->comps) {
            for (uint32_t i = 0; i < image->numcomps; i++) {
                if (image->comps[i].data) free(image->comps[i].data);
            }
            free(image->comps);
        }
        free(image);
    }
}
OPJ_BOOL opj_decode(opj_codec_t *p_decompressor, opj_stream_t *p_stream, opj_image_t *p_image) {
    if (stub_should_decode_succeed) {
        // Allocate data for comps
        uint32_t w = p_image->x1 - p_image->x0;
        uint32_t h = p_image->y1 - p_image->y0;
        for (uint32_t i = 0; i < p_image->numcomps; i++) {
             p_image->comps[i].data = (OPJ_INT32*)malloc(w * h * sizeof(OPJ_INT32));
             if (!p_image->comps[i].data) {
                 // Clean up on allocation failure
                 for (uint32_t k = 0; k < i; k++) {
                     if (p_image->comps[k].data) {
                         free(p_image->comps[k].data);
                         p_image->comps[k].data = NULL;
                     }
                 }
                 return OPJ_FALSE;
             }
             // Fill with dummy data (e.g. solid white/opaque)
             // 255 for all channels
             for (uint32_t j = 0; j < w * h; j++) {
                 p_image->comps[i].data[j] = 255;
             }
        }
        return OPJ_TRUE;
    }
    return OPJ_FALSE;
}
void opj_stream_destroy(opj_stream_t* p_stream) { if(p_stream) free(p_stream); }
void opj_destroy_codec(opj_codec_t * p_codec) { if(p_codec) free(p_codec); }
