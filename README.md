JPEG2000 Decoder for Android
==

This library provides functionality to decode JPEG2000 images on Android.

## Features

*   **JPEG2000 Decoding:** Supports decoding of JPEG2000 images on Android devices.
*   **Powered by OPENJPEG:** Utilizes the [OpenJPEG](https://github.com/uclouvain/openjpeg) library for robust and efficient decoding.
*   **WASM & Jetpack JavaScript Engine:** The native library is compiled to WebAssembly (WASM) and executed using the [Jetpack JavaScript Engine](https://developer.android.com/jetpack/androidx/releases/javascriptengine).
*   **Enhanced Security:** By running within the WASM engine's sandbox, the decoding process is isolated, offering a relatively higher level of safety compared to direct native execution.

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
    -s EXPORTED_FUNCTIONS='["_decodeToBmp", "_malloc", "_free", "_getLastError"]' \
    -o openjpeg_core.wasm
```
