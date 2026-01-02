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

int main() {
    test_argb8888();
    test_rgb565();
    return 0;
}
