JPEG2000 Decoder for Android
==

[![Build](https://github.com/keiji/jp2k-decoder-android/actions/workflows/build.yml/badge.svg)](https://github.com/keiji/jp2k-decoder-android/actions/workflows/build.yml)

This library provides functionality to decode JPEG2000 images on Android.

Historically, native image decoders have been a significant security risk. This project aims to securely perform JPEG2000 decoding within an isolated sandbox environment by running OpenJPEG as WebAssembly (WASM).

## Features

*   **JPEG2000 Decoding:** Supports decoding of JPEG2000 images on Android devices.
*   **Powered by OPENJPEG:** Utilizes the [OpenJPEG](https://github.com/uclouvain/openjpeg) library for robust and efficient decoding.
*   **WASM & Jetpack JavaScript Engine:** The native library is compiled to WebAssembly (WASM) and executed using the [Jetpack JavaScript Engine](https://developer.android.com/jetpack/androidx/releases/javascriptengine).
*   **Enhanced Security:** By running within the WASM engine's sandbox, the decoding process is isolated, offering a relatively higher level of safety compared to direct native execution.

## How to build

### 1. Initialize Submodules

Ensure you have cloned the repository with submodules, or initialize them:

```bash
git submodule update --init --recursive
```

### 2. Build OpenJPEG

First, build the OpenJPEG library. This requires [Emscripten](https://emscripten.org/) to be installed and active in your environment.

```bash
mkdir -p openjpeg/build
cd openjpeg/build
emcmake cmake .. -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DBUILD_CODEC=OFF
emmake make
cd ../..
```

### 3. Build WASM module

Compile the C wrapper and link it with the OpenJPEG library to create the WASM module.

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

## Running Tests

Unit tests for the C wrapper logic (e.g., BMP conversion) can be run without Emscripten using GCC or Clang.

```bash
bash test/run_tests.sh
```

## Generate Documentation

To generate the API documentation (KDoc), run the following command:

```bash
cd android
./gradlew :lib:dokkaHtml
```

The generated documentation will be available in `android/lib/build/dokka/html`.

## Publish

To publish the library to Maven Central Portal, export the required environment variables and run the Gradle task.

```bash
export OSSRH_USERNAME=<your-username>
export OSSRH_PASSWORD=<your-password>
cd android
./gradlew publishAggregationToCentralPortal
```
