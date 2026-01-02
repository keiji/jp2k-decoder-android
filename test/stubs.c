#include <openjpeg.h>
#include <stdio.h>
#include <stdlib.h>

// Stubs for functions used in wrapper.c but not needed for testing convert_to_bmp

opj_codec_t* opj_create_decompress(OPJ_CODEC_FORMAT format) { return NULL; }
void opj_set_default_decoder_parameters(opj_dparameters_t *parameters) {}
OPJ_BOOL opj_setup_decoder(opj_codec_t *p_codec, opj_dparameters_t *parameters) { return OPJ_TRUE; }
opj_stream_t* opj_stream_default_create(OPJ_BOOL p_is_input) { return NULL; }
void opj_stream_set_read_function(opj_stream_t* p_stream, opj_stream_read_fn p_function) {}
void opj_stream_set_user_data(opj_stream_t* p_stream, void * p_data, opj_stream_free_user_data_fn p_function) {}
void opj_stream_set_user_data_length(opj_stream_t* p_stream, OPJ_UINT64 data_length) {}
OPJ_BOOL opj_read_header(opj_stream_t *p_stream, opj_codec_t *p_codec, opj_image_t **p_image) { return OPJ_FALSE; }
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
void opj_stream_destroy(opj_stream_t* p_stream) {}
void opj_destroy_codec(opj_codec_t * p_codec) {}
