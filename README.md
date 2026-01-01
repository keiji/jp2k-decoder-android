JPEG2000 Decoder for Android
==

This library provides functionality to decode JPEG2000 images on Android.

## Features

*   **JPEG2000 Decoding:** Supports decoding of JPEG2000 images on Android devices.
*   **Powered by OPENJPEG:** Utilizes the [OpenJPEG](https://github.com/uclouvain/openjpeg) library for robust and efficient decoding.
*   **WASM & Jetpack JavaScript Engine:** The native library is compiled to WebAssembly (WASM) and executed using the [Jetpack JavaScript Engine](https://developer.android.com/jetpack/androidx/releases/javascriptengine).
*   **Enhanced Security:** By running within the WASM engine's sandbox, the decoding process is isolated, offering a relatively higher level of safety compared to direct native execution.

### Build WASM

The project uses CMake with Emscripten to build the WASM module. It creates a custom build configuration that compiles `wrapper.c` along with the necessary OpenJPEG source files (excluding encoder-specific logic where possible and relying on Link Time Optimization (LTO) to strip unused code).

```bash
# Configure
emcmake cmake -B build -DCMAKE_BUILD_TYPE=Release

# Build
cmake --build build
```

This will produce `build/openjpeg_core.wasm`.
