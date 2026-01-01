JPEG2000 Decoder for Android
==

### Build WASM

```bash
emcc -O3 wrapper.c \
    -I./openjpeg/src/lib/openjp2 \
    -I./openjpeg/build/src/lib/openjp2 \
    -L./openjpeg/build/bin \
    -lopenjp2 \
    -s WASM=1 \
    -s STANDALONE_WASM \
    --no-entry \
    -s ALLOW_MEMORY_GROWTH=1 \
    -s EXPORTED_FUNCTIONS='["_decodeRaw", "_decodeJp2", "_malloc", "_free", "_opj_image_destroy", "_getLastError"]' \
    -o openjpeg_core.wasm
```