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
implementation("dev.keiji.jp2k:jp2k-decoder-android:0.3.0")
```

### Gradle (Groovy DSL)

```groovy
implementation 'dev.keiji.jp2k:jp2k-decoder-android:0.3.0'
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

### Precaching

If you plan to perform multiple operations on the same image (e.g., getting size first, then decoding, or decoding different regions), it is efficient to transfer the image data to the WASM environment once.

```kotlin
val jp2kBytes: ByteArray = ...

decoder.precache(jp2kBytes)

// Subsequent calls do not need to pass the byte array
val size = decoder.getSize()
val bitmap = decoder.decodeImage()
```

### Getting Image Size

You can retrieve the dimensions of the image without fully decoding it.

```kotlin
// Using byte array
val size = decoder.getSize(jp2kBytes)
println("Width: ${size.width}, Height: ${size.height}")

// Or using precached data
decoder.precache(jp2kBytes)
val size = decoder.getSize()
```

### Partial Decoding (Region of Interest)

You can decode a specific region of the image by specifying the coordinates (left, top, right, bottom).

```kotlin
// Decode a region
val bitmap = decoder.decodeImage(jp2kBytes, 100, 100, 300, 300)

// Or using precached data
decoder.precache(jp2kBytes)
val bitmap = decoder.decodeImage(100, 100, 300, 300)

// Using Rect
val rect = Rect(100, 100, 300, 300)
// With byte array
val bitmap = decoder.decodeImage(jp2kBytes, rect)
// Or with precached data
decoder.precache(jp2kBytes)
val bitmap = decoder.decodeImage(rect)
```

#### Ratio-based Partial Decoding

You can also specify the region using ratios (0.0 - 1.0).

```kotlin
// Decode a region (ratio)
val bitmap = decoder.decodeImage(jp2kBytes, 0.0f, 0.0f, 0.5f, 0.5f)

// Or using precached data
decoder.precache(jp2kBytes)
val bitmap = decoder.decodeImage(0.0f, 0.0f, 0.5f, 0.5f)

// Using RectF
val rectF = RectF(0.0f, 0.0f, 0.5f, 0.5f)
// With byte array
val bitmap = decoder.decodeImage(jp2kBytes, rectF)
// Or with precached data
val bitmap = decoder.decodeImage(rectF)
```

## Configuration

You can customize the decoder behavior by passing a `Config` object to the constructor.

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `maxPixels` | `Int` | 16,000,000 | The maximum number of pixels allowed in the decoded image. |
| `maxHeapSizeBytes` | `Long` | 512 MB | The maximum size of the heap in bytes allowed for the JavaScript sandbox. |
| `maxEvaluationReturnSizeBytes` | `Int` | 256 MB | The maximum size of the return value in bytes from JavaScript evaluation. |
| `logLevel` | `Int?` | `null` | The logging level (e.g., `Log.DEBUG`, `Log.INFO`). If `null`, logging is disabled. |
| `logger` | `Logger` | `AndroidLogger` | Custom `Logger` implementation to handle log messages. |
| `maxLogLines` | `Int` | 10 | The maximum number of log lines to output per message. Excess lines will be truncated. |
| `preferDirectBinaryTransfer` | `Boolean` | `true` | Whether to prefer direct binary transfer via `provideNamedData` when supported. |

## Execution Logs (ログの見方)

When `logLevel` is set (e.g., `Log.INFO`), detailed logs regarding data transfers, performance metrics, and timing analysis are output to Logcat:

```text
2026-08-12 19:42:17.170 Jp2kDecoder I Input binary length: 147041
2026-08-12 19:42:17.170 Jp2kDecoder I J2K expression: (async () => { ... })()
2026-08-12 19:42:17.468 Jp2kDecoder I Output encoded content length: 1638472 chars
2026-08-12 19:42:17.688 Jp2kDecoder I Output encoded content (64 chars per line):
                                      Qk02wBIAAAAAADYAAAAoAAAAgAIAACD+//8BACAAAAAAAAAAAAAAAAAAAAAAAAAA
                                      ... (truncated 25593 lines) ...
                                      QEgS/
2026-08-12 19:42:17.726 Jp2kDecoder I Output data length: 1228854 bytes
2026-08-12 19:42:17.727 Jp2kDecoder I Input transfer start delay (Kotlin -> JS start): 2.00 ms
2026-08-12 19:42:17.727 Jp2kDecoder I Output transfer delay (JS finish -> Kotlin receive): 20.00 ms
2026-08-12 19:42:17.727 Jp2kDecoder I Output Kotlin decode time: 37.97 ms
2026-08-12 19:42:17.728 Jp2kDecoder I Performance: inputSize=0B totalTime=545ms
                                          dataTransferTime=261ms jsDecodeTime=0ms jsEncodeTime=144ms
                                          wasmHeapSize=7MB outputImage=1228854B
2026-08-12 19:42:17.728 Jp2kDecoder I Pre-process: 0.0 ms, WASM: 95.0 ms, Post-process: 144.0 ms
2026-08-12 19:42:17.728 Jp2kDecoder I decodeImage() finished in 546 msec
```

### Explanation of Log Fields

| Log Message / Metric | Description (説明) |
| :--- | :--- |
| `Input binary length` / `Input data length` | Size of the input raw JPEG 2000 byte array in bytes (Kotlin側から入力したデータサイズ)。 |
| `Input encoded content length` | Length of the encoded string in characters when string-mediated transfer is used. |
| `Input encoded content (64 chars per line)` | Encoded string of input data (formatted 64 chars/line). Truncated if exceeding `maxLogLines`. |
| `Input JS decode time` | Time spent decoding input string payload to `Uint8Array` in JavaScript (`ms`). |
| `Input transfer start delay (Kotlin -> JS start)` | Overhead delay from Kotlin request initiation to JS execution start (`ms`). |
| `Output transfer delay (JS finish -> Kotlin receive)` | Overhead delay from JS execution completion to Kotlin result receipt (`ms`). |
| `Output encoded content length` | Length of the decoded output image payload (e.g. BMP) in characters (`chars`). |
| `Output encoded content (64 chars per line)` | Encoded output string (formatted 64 chars/line). Truncated if exceeding `maxLogLines`. |
| `Output Kotlin decode time` | Time spent decoding the return string payload in Kotlin (`ms`). |
| `Output data length` | Size of the decoded output bitmap bytes in bytes. |
| `Performance: ...` / `Pre-process / WASM / Post-process` | Breakdown of WASM execution, JS pre/post-processing, heap size, and total time. |

## Color Formats

The library supports the following color formats for the output `Bitmap`. You can specify the format in `decodeImage`.

*   **`ColorFormat.ARGB8888`** (Default): High quality, 4 bytes per pixel. Supports transparency.
*   **`ColorFormat.RGB565`**: Lower quality, 2 bytes per pixel. No transparency support.

## State Transitions

The decoder manages its internal state to ensure thread safety and resource management. The states are:

*   **`Uninitialized`**: The initial state.
*   **`Initializing`**: `init()` has been called and the JavaScript sandbox is starting.
*   **`Initialized`**: Ready to decode images.
*   **`Processing`**: Currently executing a task (e.g., decoding, checking memory usage).
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

### Test Coverage

#### Android Unit Test Coverage

To measure the code coverage for Android unit tests, run the following command:

```bash
cd android
./gradlew lib:jacocoTestReport
```

The HTML report will be generated at `android/lib/build/reports/jacoco/jacocoTestReport/html/index.html`.

#### C/Native Test Coverage

To measure the code coverage for the C/Native code (wrapper.c), run the following command:

```bash
./test/run_coverage.sh
```

The summary will be printed to stdout.

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
