#include <openjpeg.h>
#include <stdio.h>
#include <stdlib.h>

// Stubs for functions used in wrapper.c but not needed for testing convert_to_bmp

int stub_should_header_succeed = 0;
uint32_t stub_width = 0;
uint32_t stub_height = 0;

int stub_should_decompress_create_succeed = 1;
int stub_should_setup_succeed = 1;

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
        return OPJ_TRUE;
    }
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
OPJ_BOOL opj_decode(opj_codec_t *p_decompressor, opj_stream_t *p_stream, opj_image_t *p_image) { return OPJ_FALSE; }
void opj_stream_destroy(opj_stream_t* p_stream) { if(p_stream) free(p_stream); }
void opj_destroy_codec(opj_codec_t * p_codec) { if(p_codec) free(p_codec); }
