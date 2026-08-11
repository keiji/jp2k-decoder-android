# AGENTS.md — JPEG2000 Decoder Android Library

## Repo Structure
- `android/` — Gradle (Kotlin DSL), two modules: `:lib` (library, published to Maven Central) and `:app` (Compose sample)
- `wrapper.c` — OpenJPEG C wrapper, compiled to WASM via Emscripten
- `openjpeg/` — git submodule (`uclouvain/openjpeg`); **must** run `git submodule update --init --recursive`
- `test/` — standalone C unit tests (GCC, no Emscripten); `test_wrapper.c` includes `wrapper.c` via `#include` to test static functions (intentional)

## Prerequisites
- **JDK 21** (CI uses Temurin)
- **Android SDK**: compileSdk 36, minSdk 26
- **Emscripten** (`emcc`/`emcmake`/`emmake`) — only for WASM builds; not needed for JVM tests or Android builds

## Build Commands
- **WASM** (build once or when `wrapper.c` changes):
  1. `git submodule update --init --recursive`
  2. OpenJPEG: `mkdir -p openjpeg/build && cd openjpeg/build && emcmake cmake .. -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DBUILD_CODEC=OFF && emmake make && cd ../..`
  3. Wrapper: `emcc -O3 wrapper.c -I./openjpeg/src/lib/openjp2 -I./openjpeg/build/src/lib/openjp2 -L./openjpeg/build/bin -lopenjp2 -s WASM=1 -s STANDALONE_WASM --no-entry -s ALLOW_MEMORY_GROWTH=1 -s INITIAL_MEMORY=4194304 -s TOTAL_STACK=1048576 -s EXPORTED_FUNCTIONS='["_decodeToBmp", "_malloc", "_free", "_getLastError"]' -o openjpeg_core.wasm`
  4. Copy: `cp openjpeg_core.wasm android/lib/src/main/assets/`
- **C tests**: `bash test/run_tests.sh` | **C coverage**: `bash test/run_coverage.sh` (gcov + custom awk, not LCOV)
- **Android unit tests**: `cd android && ./gradlew :lib:testDebugUnitTest`
- **Android coverage**: `cd android && ./gradlew :lib:jacocoTestReport` → report at `android/lib/build/reports/jacoco/jacocoTestReport/html/index.html`
- **Build lib**: `cd android && ./gradlew :lib:assembleDebug`
- **Dokka**: `cd android && ./gradlew :lib:dokkaHtml`
- **Publish** (set `CENTRAL_PORTAL_USERNAME`, `CENTRAL_PORTAL_PASSWORD`): `cd android && ./gradlew publishAggregationToCentralPortal`

## Critical Architecture (non-obvious)
- **No JNA/JNI** — Android side uses `JavaScriptEngine` sandbox only (no ndk-build, no CMake for Android)
- **WASM bundled as asset** (`openjpeg_core.wasm`), exchanged via Base64 string through JSON; expect encoding overhead. Use `precache()` for repeated ops on the same image to amortize transfer cost.
- **Data flow**: `ByteArray` → Base64 → JS eval (JSIsolate) → Base64 decode → malloc WASM heap → OpenJPEG decode → BMP conversion → malloc BMP buffer → read WASM memory → Base64 encode → JSON → Kotlin parse → `BitmapFactory.decodeByteArray()`
- **JS bridge** injects inline scripts at init: WASI stubs (`wasi_snapshot_preview1`), Base64 converters, WASM instantiation/bindings. Exports: `decodeJ2K`, `decodeJ2KRatio`, `decodeJ2KWithCache`, `decodeJ2KWithCacheRatio`, `getSize`, `getSizeWithCache`, `getMemoryUsage`, `setData`
- **Two APIs**: `Jp2kDecoder` (Kotlin coroutines, `Mutex.withLock`) preferred; `Jp2kDecoderAsync` (callback + `Executor`, queues during processing) for Java. Both share `Jp2kSandbox` (singleton) for connection reuse.
- **State machine**: `Uninitialized → Initializing → Initialized → Processing → Initialized` (repeat). Any state → `Release()` → `Released` (terminal). `init()` failure resets to `Uninitialized`.

## Conventions & Quirks
- Dokka V2 plugin mode explicitly enabled in `gradle.properties`; `android.nonTransitiveRClass=true`
- CI (`.github/workflows/build.yml`): lint, build, unit tests, coverage, WASM generation on push/PR
- Dependabot: groups androidx, kotlin, agp separately
- JVM tests: JUnit 4 + Mockito (core, inline, kotlin). **No Robolectric.**
- Test fixtures: `TestUtils.kt` provides `TestListenableFuture`, `FailingListenableFuture`
