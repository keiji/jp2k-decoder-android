#include <stdio.h>
#include <stdlib.h>
#include <assert.h>
#include "emscripten.h"

// Include wrapper.c to access static functions
// This is a bit hacky but effective for unit testing static functions
#include "../wrapper.c"

// Helper to create a mock opj_image_t
opj_image_t* create_mock_image(uint32_t width, uint32_t height, int numcomps, int with_alpha) {
    opj_image_t* image = (opj_image_t*)calloc(1, sizeof(opj_image_t));
    image->x0 = 0;
    image->y0 = 0;
    image->x1 = width;
    image->y1 = height;
    image->numcomps = numcomps;
    image->comps = (opj_image_comp_t*)calloc(numcomps, sizeof(opj_image_comp_t));

    for (int i = 0; i < numcomps; i++) {
        image->comps[i].data = (int32_t*)malloc(width * height * sizeof(int32_t));
        // Initialize with 0
        memset(image->comps[i].data, 0, width * height * sizeof(int32_t));
    }

    if (with_alpha && numcomps > 3) {
        image->comps[3].alpha = 1;
    }

    return image;
}

void test_argb8888() {
    printf("Testing ARGB8888...\n");
    uint32_t width = 2;
    uint32_t height = 2;
    opj_image_t* image = create_mock_image(width, height, 4, 1);

    // Set specific pixels
    // Pixel 0 (Top-Left): Red (255, 0, 0, 128)
    image->comps[0].data[0] = 255;
    image->comps[1].data[0] = 0;
    image->comps[2].data[0] = 0;
    image->comps[3].data[0] = 128;

    // Pixel 1 (Top-Right): Green (0, 255, 0, 255)
    image->comps[0].data[1] = 0;
    image->comps[1].data[1] = 255;
    image->comps[2].data[1] = 0;
    image->comps[3].data[1] = 255;

    // Pixel 2 (Bottom-Left): Blue (0, 0, 255, 255)
    image->comps[0].data[2] = 0;
    image->comps[1].data[2] = 0;
    image->comps[2].data[2] = 255;
    image->comps[3].data[2] = 255;

    // Pixel 3 (Bottom-Right): White (255, 255, 255, 255)
    image->comps[0].data[3] = 255;
    image->comps[1].data[3] = 255;
    image->comps[2].data[3] = 255;
    image->comps[3].data[3] = 255;

    uint8_t* bmp = convert_image_to_bmp(image, COLOR_FORMAT_ARGB8888);
    assert(bmp != NULL);

    // Check header
    // Offset 0: 'BM'
    assert(bmp[0] == 0x42);
    assert(bmp[1] == 0x4D);

    // File size at offset 2
    uint32_t fileSize = *(uint32_t*)(bmp + 2);
    // Header 14 + DIB 40 = 54
    // Pixels: 2*2 * 4 = 16 bytes
    // Total 70 bytes
    if (fileSize != 54 + 16) {
        printf("Expected file size 70, got %u\n", fileSize);
        assert(fileSize == 54 + 16);
    }

    // Pixel data at offset 54
    uint8_t* pixels = bmp + 54;

    // Code uses BGRA order for ARGB8888

    // Pixel 0: B=0, G=0, R=255, A=128
    assert(pixels[0] == 0);   // B
    assert(pixels[1] == 0);   // G
    assert(pixels[2] == 255); // R
    assert(pixels[3] == 128); // A

    // Pixel 1: B=0, G=255, R=0, A=255
    assert(pixels[4] == 0);
    assert(pixels[5] == 255);
    assert(pixels[6] == 0);
    assert(pixels[7] == 255);

    // Pixel 2: B=255, G=0, R=0, A=255
    assert(pixels[8] == 255);
    assert(pixels[9] == 0);
    assert(pixels[10] == 0);
    assert(pixels[11] == 255);

    // Pixel 3: B=255, G=255, R=255, A=255
    assert(pixels[12] == 255);
    assert(pixels[13] == 255);
    assert(pixels[14] == 255);
    assert(pixels[15] == 255);

    free(bmp);
    opj_image_destroy(image); // Uses our stub
    printf("ARGB8888 Passed.\n");
}

void test_rgb565() {
    printf("Testing RGB565...\n");
    uint32_t width = 2;
    uint32_t height = 2;
    opj_image_t* image = create_mock_image(width, height, 3, 0);

    // Pixel 0: Red (255, 0, 0)
    image->comps[0].data[0] = 255;
    image->comps[1].data[0] = 0;
    image->comps[2].data[0] = 0;

    // Pixel 1: Green (0, 255, 0)
    image->comps[0].data[1] = 0;
    image->comps[1].data[1] = 255;
    image->comps[2].data[1] = 0;

    // Pixel 2: Blue (0, 0, 255)
    image->comps[0].data[2] = 0;
    image->comps[1].data[2] = 0;
    image->comps[2].data[2] = 255;

    // Pixel 3: White (255, 255, 255)
    image->comps[0].data[3] = 255;
    image->comps[1].data[3] = 255;
    image->comps[2].data[3] = 255;

    uint8_t* bmp = convert_image_to_bmp(image, COLOR_FORMAT_RGB565);
    assert(bmp != NULL);

    // Header size: 14 + 40 + 12 = 66
    // Pixels: 2 * 2bytes = 4 bytes per row. 2 rows. 8 bytes.
    // Total 74 bytes.
    uint32_t fileSize = *(uint32_t*)(bmp + 2);
    if (fileSize != 66 + 8) {
        printf("Expected file size 74, got %u\n", fileSize);
        assert(fileSize == 66 + 8);
    }

    uint8_t* pixels = bmp + 66;

    // RGB565: R(5) G(6) B(5)
    // Red: 11111 000000 00000 -> 0xF800
    // Green: 00000 111111 00000 -> 0x07E0
    // Blue: 00000 000000 11111 -> 0x001F
    // White: 11111 111111 11111 -> 0xFFFF

    uint16_t* p16 = (uint16_t*)pixels;

    if (p16[0] != 0xF800) {
        printf("Expected Red 0xF800, got 0x%04X\n", p16[0]);
        assert(p16[0] == 0xF800);
    }
    if (p16[1] != 0x07E0) {
        printf("Expected Green 0x07E0, got 0x%04X\n", p16[1]);
        assert(p16[1] == 0x07E0);
    }
    if (p16[2] != 0x001F) {
        printf("Expected Blue 0x001F, got 0x%04X\n", p16[2]);
        assert(p16[2] == 0x001F);
    }
    if (p16[3] != 0xFFFF) {
        printf("Expected White 0xFFFF, got 0x%04X\n", p16[3]);
        assert(p16[3] == 0xFFFF);
    }

    free(bmp);
    opj_image_destroy(image);
    printf("RGB565 Passed.\n");
}

void test_grayscale() {
    printf("Testing Grayscale (1ch)...\n");
    uint32_t width = 2;
    uint32_t height = 1;
    opj_image_t* image = create_mock_image(width, height, 1, 0);

    // Pixel 0: 0
    image->comps[0].data[0] = 0;
    // Pixel 1: 255
    image->comps[0].data[1] = 255;

    // 1. Test ARGB8888 Expansion (Gray -> R=G=B, A=255)
    uint8_t* bmp = convert_image_to_bmp(image, COLOR_FORMAT_ARGB8888);
    assert(bmp != NULL);
    uint8_t* pixels = bmp + 54;

    // Pixel 0: B=0, G=0, R=0, A=255
    assert(pixels[0] == 0);
    assert(pixels[1] == 0);
    assert(pixels[2] == 0);
    assert(pixels[3] == 255);

    // Pixel 1: B=255, G=255, R=255, A=255
    assert(pixels[4] == 255);
    assert(pixels[5] == 255);
    assert(pixels[6] == 255);
    assert(pixels[7] == 255);

    free(bmp);

    // 2. Test RGB565 Expansion (Gray -> R=G=B)
    bmp = convert_image_to_bmp(image, COLOR_FORMAT_RGB565);
    assert(bmp != NULL);
    uint16_t* p16 = (uint16_t*)(bmp + 66);

    // Pixel 0: Black (0x0000)
    assert(p16[0] == 0x0000);

    // Pixel 1: White (0xFFFF)
    assert(p16[1] == 0xFFFF);

    free(bmp);
    opj_image_destroy(image);
    printf("Grayscale Passed.\n");
}

void test_grayscale_alpha() {
    printf("Testing Grayscale + Alpha (2ch)...\n");
    uint32_t width = 1;
    uint32_t height = 1;
    opj_image_t* image = create_mock_image(width, height, 2, 0);

    // Gray: 100
    image->comps[0].data[0] = 100;
    // Alpha: 200 (explicitly marked as alpha by our logic check if we set the flag,
    // OR just by position if we assume 2nd channel is alpha?
    // My code says: if (image->comps[1].alpha != 0) { a_data = image->comps[1].data; }
    // So I must set the alpha flag in the mock for it to be picked up as Alpha.
    image->comps[1].data[0] = 200;
    image->comps[1].alpha = 1;

    uint8_t* bmp = convert_image_to_bmp(image, COLOR_FORMAT_ARGB8888);
    assert(bmp != NULL);
    uint8_t* pixels = bmp + 54;

    // B=100, G=100, R=100, A=200
    assert(pixels[0] == 100);
    assert(pixels[1] == 100);
    assert(pixels[2] == 100);
    assert(pixels[3] == 200);

    free(bmp);
    opj_image_destroy(image);
    printf("Grayscale + Alpha Passed.\n");
}

void test_multichannel() {
    printf("Testing Multi-channel (5ch)...\n");
    uint32_t width = 1;
    uint32_t height = 1;
    opj_image_t* image = create_mock_image(width, height, 5, 0);

    // Set pixel values
    // Comp 0 (R): 255
    image->comps[0].data[0] = 255;
    // Comp 1 (G): 0
    image->comps[1].data[0] = 0;
    // Comp 2 (B): 0
    image->comps[2].data[0] = 0;
    // Comp 3 (A): 128 (treated as alpha if flag set, or index 3 if not)
    // In create_mock_image, we didn't set alpha flag for index 3 unless called with with_alpha=1
    // But get_alpha_component returns comps[3].data if no alpha flag is found and numcomps > 3
    image->comps[3].data[0] = 128;
    // Comp 4 (Ignored): 100
    image->comps[4].data[0] = 100;

    uint8_t* bmp = convert_image_to_bmp(image, COLOR_FORMAT_ARGB8888);
    assert(bmp != NULL);
    uint8_t* pixels = bmp + 54;

    // Check BGRA
    // B=0 (from comp 2)
    assert(pixels[0] == 0);
    // G=0 (from comp 1)
    assert(pixels[1] == 0);
    // R=255 (from comp 0)
    assert(pixels[2] == 255);
    // A=128 (from comp 3)
    assert(pixels[3] == 128);

    free(bmp);
    opj_image_destroy(image);
    printf("Multi-channel Passed.\n");
}

void test_input_validation() {
    printf("Testing Input Validation...\n");

    uint8_t dummy_data[100] = {0};
    uint8_t* result;

    // Case 1: Input size too small (< MIN_INPUT_SIZE)
    result = decodeToBmp(dummy_data, 11, 0, 1000, COLOR_FORMAT_ARGB8888, 0, 0, 0, 0);
    assert(result == NULL);
    assert(last_error == ERR_INPUT_DATA_SIZE);

    // Case 2: ARGB8888 size check (data_len > max_heap / 4)
    // max_heap = 100 -> max_input = 25
    // input = 26 -> Error
    result = decodeToBmp(dummy_data, 26, 0, 100, COLOR_FORMAT_ARGB8888, 0, 0, 0, 0);
    assert(result == NULL);
    assert(last_error == ERR_INPUT_DATA_SIZE);

    // Case 3: ARGB8888 size check success boundary
    // max_heap = 100 -> max_input = 25
    // input = 25 -> OK (proceeds to decode, fails there)
    result = decodeToBmp(dummy_data, 25, 0, 100, COLOR_FORMAT_ARGB8888, 0, 0, 0, 0);
    assert(result == NULL);
    // Since stubs opj_read_header returns false, we expect ERR_HEADER
    // But verify it PASSED the size check (NOT ERR_INPUT_DATA_SIZE)
    assert(last_error == ERR_HEADER);

    // Case 4: RGB565 size check (data_len > max_heap / 2)
    // max_heap = 100 -> max_input = 50
    // input = 51 -> Error
    result = decodeToBmp(dummy_data, 51, 0, 100, COLOR_FORMAT_RGB565, 0, 0, 0, 0);
    assert(result == NULL);
    assert(last_error == ERR_INPUT_DATA_SIZE);

    // Case 5: RGB565 size check success boundary
    // max_heap = 100 -> max_input = 50
    // input = 50 -> OK (proceeds to decode)
    result = decodeToBmp(dummy_data, 50, 0, 100, COLOR_FORMAT_RGB565, 0, 0, 0, 0);
    assert(result == NULL);
    assert(last_error == ERR_HEADER);

    printf("Input Validation Passed.\n");
}

extern int stub_should_header_succeed;
extern uint32_t stub_width;
extern uint32_t stub_height;
extern int stub_should_decompress_create_succeed;
extern int stub_should_setup_succeed;
extern int stub_should_decode_succeed;

void test_jp2_signature() {
    printf("Testing JP2 Signature...\n");
    // JP2 Signature: 00 00 00 0C ...
    uint8_t dummy_data[20] = {0x00, 0x00, 0x00, 0x0C, 0x00};

    // We expect it to reach decode_internal and fail at opj_read_header (since we didn't set stub_should_header_succeed)
    // But we want to ensure it calls opj_create_decompress(OPJ_CODEC_JP2)
    // We can't verify the argument to opj_create_decompress easily without mocking it with arg check.
    // However, we know get_codec_format is called.
    // This test is mainly to execute the line in get_codec_format.

    uint8_t* result = decodeToBmp(dummy_data, 20, 0, 1000, COLOR_FORMAT_ARGB8888, 0, 0, 0, 0);
    assert(result == NULL);
    assert(last_error == ERR_HEADER);
    printf("JP2 Signature Passed.\n");
}

void test_pixel_limit() {
    printf("Testing Pixel Limit...\n");
    uint8_t dummy_data[20] = {0};

    // Setup success stubs for header
    stub_should_header_succeed = 1;
    stub_width = 20;
    stub_height = 20;
    // Pixels = 400.

    // Limit = 100.
    uint8_t* result = decodeToBmp(dummy_data, 20, 100, 1000, COLOR_FORMAT_ARGB8888, 0, 0, 0, 0);
    assert(result == NULL);
    assert(last_error == ERR_PIXEL_DATA_SIZE);

    printf("Pixel Limit Passed.\n");
    stub_should_header_succeed = 0;
}

void test_full_decode_success() {
    printf("Testing Full Decode Success...\n");
    uint8_t dummy_data[20] = {0};

    stub_should_header_succeed = 1;
    stub_should_decode_succeed = 1;
    stub_width = 10;
    stub_height = 10;

    // ARGB8888
    uint8_t* result = decodeToBmp(dummy_data, 20, 0, 10000, COLOR_FORMAT_ARGB8888, 0, 0, 0, 0);
    assert(result != NULL);

    // Check BMP header briefly
    assert(result[0] == 0x42);
    assert(result[1] == 0x4D);

    free(result);

    // RGB565
    result = decodeToBmp(dummy_data, 20, 0, 10000, COLOR_FORMAT_RGB565, 0, 0, 0, 0);
    assert(result != NULL);
    free(result);

    printf("Full Decode Success Passed.\n");
    stub_should_header_succeed = 0;
    stub_should_decode_succeed = 0;
}

void test_getSize() {
    printf("Testing getSize...\n");
    uint8_t dummy_data[20] = {0}; // > MIN_INPUT_SIZE (12)

    // Case 1: Header failure
    stub_should_header_succeed = 0;
    uint32_t* result = getSize(dummy_data, 20);
    assert(result == NULL);
    assert(last_error == ERR_HEADER);

    // Case 2: Success
    stub_should_header_succeed = 1;
    stub_width = 1920;
    stub_height = 1080;

    result = getSize(dummy_data, 20);
    assert(result != NULL);
    assert(result[0] == 1920);
    assert(result[1] == 1080);

    free(result);
    printf("getSize Passed.\n");

    // Reset stubs
    stub_should_header_succeed = 0;
}

void test_decode_failures() {
    printf("Testing Decode Failures...\n");
    uint8_t dummy_data[100] = {0};

    // 1. Null Data
    printf("Debug: 1. Null Data\n");
    uint8_t* result = decodeToBmp(NULL, 100, 0, 1000, COLOR_FORMAT_ARGB8888, 0, 0, 0, 0);
    assert(result == NULL);
    assert(last_error == ERR_INPUT_DATA_SIZE);

    // 2. Zero Length
    printf("Debug: 2. Zero Length\n");
    result = decodeToBmp(dummy_data, 0, 0, 1000, COLOR_FORMAT_ARGB8888, 0, 0, 0, 0);
    assert(result == NULL);
    assert(last_error == ERR_INPUT_DATA_SIZE);

    // 3. Create Decoder Failure
    printf("Debug: 3. Create Decoder Failure\n");
    stub_should_decompress_create_succeed = 0;
    result = decodeToBmp(dummy_data, 100, 0, 1000, COLOR_FORMAT_ARGB8888, 0, 0, 0, 0);
    assert(result == NULL);
    assert(last_error == ERR_DECODER_SETUP);
    stub_should_decompress_create_succeed = 1; // Reset

    // 4. Setup Decoder Failure
    printf("Debug: 4. Setup Decoder Failure\n");
    stub_should_setup_succeed = 0;
    result = decodeToBmp(dummy_data, 100, 0, 1000, COLOR_FORMAT_ARGB8888, 0, 0, 0, 0);
    assert(result == NULL);
    assert(last_error == ERR_DECODER_SETUP);
    stub_should_setup_succeed = 1; // Reset

    printf("Decode Failures Passed.\n");
}

void test_bounds_check() {
    printf("Testing Bounds Check...\n");
    uint8_t dummy_data[20] = {0};

    // Setup success stubs
    stub_should_header_succeed = 1;
    stub_width = 100;
    stub_height = 100;

    // 1. Valid Full Decode (explicit 0s)
    uint8_t* result = decodeToBmp(dummy_data, 20, 0, 1000, COLOR_FORMAT_ARGB8888, 0, 0, 0, 0);
    assert(result == NULL);
    // decode stub returns FALSE, so ERR_DECODE is expected, NOT ERR_REGION_OUT_OF_BOUNDS
    assert(last_error == ERR_DECODE);

    // 2. Valid Partial Decode
    result = decodeToBmp(dummy_data, 20, 0, 1000, COLOR_FORMAT_ARGB8888, 10, 10, 20, 20);
    assert(result == NULL);
    assert(last_error == ERR_DECODE);

    // 3. Invalid: x1 > width
    result = decodeToBmp(dummy_data, 20, 0, 1000, COLOR_FORMAT_ARGB8888, 0, 0, 101, 20);
    assert(result == NULL);
    assert(last_error == ERR_REGION_OUT_OF_BOUNDS);

    // 4. Invalid: y1 > height
    result = decodeToBmp(dummy_data, 20, 0, 1000, COLOR_FORMAT_ARGB8888, 0, 0, 100, 101);
    assert(result == NULL);
    assert(last_error == ERR_REGION_OUT_OF_BOUNDS);

    // 5. Invalid: x0 < 0 (uint32, so large number check? No, passed as uint32, always >= 0)
    // Checking bounds relative to x1.
    // Invalid: x0 >= x1
    result = decodeToBmp(dummy_data, 20, 0, 1000, COLOR_FORMAT_ARGB8888, 20, 0, 20, 20);
    assert(result == NULL);
    assert(last_error == ERR_REGION_OUT_OF_BOUNDS);

    // 6. Invalid: x0 >= x1 (bigger)
    result = decodeToBmp(dummy_data, 20, 0, 1000, COLOR_FORMAT_ARGB8888, 21, 0, 20, 20);
    assert(result == NULL);
    assert(last_error == ERR_REGION_OUT_OF_BOUNDS);

    printf("Bounds Check Passed.\n");

    stub_should_header_succeed = 0;
}

void test_getsize_failures() {
    printf("Testing getSize Failures...\n");
    uint8_t dummy_data[100] = {0};

    // 1. Null Data
    uint32_t* result = getSize(NULL, 100);
    assert(result == NULL);
    assert(last_error == ERR_INPUT_DATA_SIZE);

    // 2. Zero Length
    result = getSize(dummy_data, 0);
    assert(result == NULL);
    assert(last_error == ERR_INPUT_DATA_SIZE);

    printf("getSize Failures Passed.\n");
}

void test_ratio_decode() {
    printf("Testing Ratio Decode...\n");
    uint8_t dummy_data[20] = {0};

    // Setup success stubs
    stub_should_header_succeed = 1;
    stub_width = 100;
    stub_height = 200;

    // Test 1: Ratio 0.0, 0.0, 0.5, 0.5 -> 0, 0, 50, 100
    // We expect decode_internal to call opj_set_decode_area(..., 0, 0, 50, 100)
    // But we can't verify that directly in this unit test structure unless we mock opj_set_decode_area and record args.
    // However, we can verify that it does NOT return OUT_OF_BOUNDS for valid ratio.

    // 0.0, 0.0, 0.5, 0.5
    uint8_t* result = decodeToBmpWithRatio(dummy_data, 20, 0, 1000, COLOR_FORMAT_ARGB8888, 0.0, 0.0, 0.5, 0.5);
    // It should fail at opj_decode stage (ERR_DECODE), not REGION_OUT_OF_BOUNDS
    assert(result == NULL);
    assert(last_error == ERR_DECODE);

    // Test 2: Invalid Ratio (resulting in out of bounds?)
    // 0.0, 0.0, 1.1, 1.1 -> 0, 0, 100, 200 (clamped)
    // My implementation clamps to width/height.
    // So 1.1 becomes 1.0 (width).
    // So it should still be valid.
    result = decodeToBmpWithRatio(dummy_data, 20, 0, 1000, COLOR_FORMAT_ARGB8888, 0.0, 0.0, 1.1, 1.1);
    assert(result == NULL);
    assert(last_error == ERR_DECODE);

    // Test 3: "Empty" ratio? 0.5, 0.5, 0.5, 0.5 -> x0=50, x1=50 -> is_partial=0?
    // x0=50, y0=100, x1=50, y1=100.
    // is_partial check: (ux1 != 0 || uy1 != 0) -> true.
    // bounds check: ux0 >= ux1 -> 50 >= 50 -> fail.
    result = decodeToBmpWithRatio(dummy_data, 20, 0, 1000, COLOR_FORMAT_ARGB8888, 0.5, 0.5, 0.5, 0.5);
    assert(result == NULL);
    assert(last_error == ERR_REGION_OUT_OF_BOUNDS);

    printf("Ratio Decode Passed.\n");
    stub_should_header_succeed = 0;
}

int main() {
    test_argb8888();
    test_rgb565();
    test_grayscale();
    test_grayscale_alpha();
    test_multichannel();
    test_input_validation();
    test_getSize();
    test_decode_failures();
    test_bounds_check();
    test_getsize_failures();
    test_ratio_decode();
    test_jp2_signature();
    test_pixel_limit();
    test_full_decode_success();
    return 0;
}
