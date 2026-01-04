JPEG2000 Decoder for Android
==

[![Build](https://github.com/keiji/jp2k-decoder-android/actions/workflows/build.yml/badge.svg)](https://github.com/keiji/jp2k-decoder-android/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.keiji.jp2k/jp2k-decoder-android.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.keiji.jp2k/jp2k-decoder-android)

This library provides functionality to decode JPEG2000 images on Android.

Historically, native image decoders have been a significant security risk. This project aims to securely perform JPEG2000 decoding within an isolated sandbox environment by running OpenJPEG as WebAssembly (WASM).

## Features

*   **JPEG2000 Decoding:** Supports decoding of JPEG2000 images on Android devices.
*   **Powered by OPENJPEG:** Utilizes the [OpenJPEG](https://github.com/uclouvain/openjpeg) library for robust and efficient decoding.
*   **WASM & Jetpack JavaScript Engine:** The native library is compiled to WebAssembly (WASM) and executed using the [Jetpack JavaScript Engine](https://developer.android.com/jetpack/androidx/releases/javascriptengine).
*   **Enhanced Security:** By running within the WASM engine's sandbox, the decoding process is isolated, offering a relatively higher level of safety compared to direct native execution.

## Installation

The binaries are available on Maven Central.

To install the library, add the dependency to your module's `build.gradle.kts` (Kotlin DSL) or `build.gradle` (Groovy DSL).

### Gradle (Kotlin DSL)

```kotlin
implementation("dev.keiji.jp2k:jp2k-decoder-android:0.2.0")
```

### Gradle (Groovy DSL)

```groovy
implementation 'dev.keiji.jp2k:jp2k-decoder-android:0.2.0'
```

## Usage

### Jp2kDecoder (Kotlin Coroutines)

`Jp2kDecoder` is designed for use with Kotlin Coroutines. It implements `AutoCloseable`, so it can be used with the `use` block for automatic resource management.

```kotlin
val context: Context = ... // Application Context
val jp2kBytes: ByteArray = ... // JPEG2000 image data

val decoder = Jp2kDecoder(Config())
decoder.init(context)

val bitmap = decoder.decodeImage(jp2kBytes)

decoder.close()
```

Or using `use`:

```kotlin
Jp2kDecoder(Config()).use { decoder ->
    decoder.init(context)
    val bitmap = decoder.decodeImage(jp2kBytes)
    // Use bitmap
}
```

### Jp2kDecoderAsync (Java / Callbacks)

`Jp2kDecoderAsync` provides a callback-based API, making it suitable for Java or non-coroutine environments.

```java
Context context = ...; // Application Context
byte[] jp2kBytes = ...; // JPEG2000 image data

Jp2kDecoderAsync decoder = new Jp2kDecoderAsync(new Config(), Executors.newSingleThreadExecutor());

decoder.init(context, new Callback<Unit>() {
    @Override
    public void onSuccess(Unit result) {
        decoder.decodeImage(jp2kBytes, new Callback<Bitmap>() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                // Use bitmap
                decoder.close();
            }

            @Override
            public void onError(Exception e) {
                // Handle decode error
                decoder.close();
            }
        });
    }

    @Override
    public void onError(Exception e) {
        // Handle init error
    }
});
```

## Configuration

You can customize the decoder behavior by passing a `Config` object to the constructor.

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `maxPixels` | `Int` | 16,000,000 | The maximum number of pixels allowed in the decoded image. |
| `maxHeapSizeBytes` | `Long` | 512 MB | The maximum size of the heap in bytes allowed for the JavaScript sandbox. |
| `maxEvaluationReturnSizeBytes` | `Int` | 256 MB | The maximum size of the return value in bytes from JavaScript evaluation. |
| `logLevel` | `Int?` | `null` | The logging level (e.g., `Log.DEBUG`). If `null`, logging is disabled. |

## Color Formats

The library supports the following color formats for the output `Bitmap`. You can specify the format in `decodeImage`.

*   **`ColorFormat.ARGB8888`** (Default): High quality, 4 bytes per pixel. Supports transparency.
*   **`ColorFormat.RGB565`**: Lower quality, 2 bytes per pixel. No transparency support.

## State Transitions

The decoder manages its internal state to ensure thread safety and resource management. The states are:

*   **`Uninitialized`**: The initial state.
*   **`Initializing`**: `init()` has been called and the JavaScript sandbox is starting.
*   **`Initialized`**: Ready to decode images.
*   **`Processing`**: Currently decoding an image.
*   **`Releasing`**: `release()` or `close()` has been called.
*   **`Released`**: Resources have been freed. The decoder cannot be used anymore.

Calls to `decodeImage` are allowed only when the state is `Initialized` (or `Processing` for Async, which queues requests).

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
    -s INITIAL_MEMORY=4194304 \
    -s TOTAL_STACK=1048576 \
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

## Design Document

For more details on the internal architecture, data flow, and state management, please refer to the [Design Document](docs/design.md).

*Note: The design document is currently available in Japanese only.*

## Publish

To publish the library to Maven Central Portal, export the required environment variables and run the Gradle task.

```bash
export CENTRAL_PORTAL_USERNAME=<your-username>
export CENTRAL_PORTAL_PASSWORD=<your-password>
cd android
./gradlew publishAggregationToCentralPortal
```

## License

```
Copyright 2026 ARIYAMA Keiji

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS “AS IS” AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
