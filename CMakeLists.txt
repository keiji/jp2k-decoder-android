cmake_minimum_required(VERSION 3.10)
project(openjpeg_wasm C)

# OpenJPEG Version
set(OPENJPEG_VERSION_MAJOR 2)
set(OPENJPEG_VERSION_MINOR 5)
set(OPENJPEG_VERSION_BUILD 0)
set(PACKAGE_VERSION "2.5.0")

# Configuration for opj_config_private.h
# Emscripten supports posix_memalign and malloc.h
set(OPJ_HAVE_MALLOC_H 1)
set(OPJ_HAVE_POSIX_MEMALIGN 1)

# Generate configuration files
configure_file(
  ${CMAKE_CURRENT_SOURCE_DIR}/openjpeg/src/lib/openjp2/opj_config.h.cmake.in
  ${CMAKE_CURRENT_BINARY_DIR}/src/lib/openjp2/opj_config.h
)

configure_file(
  ${CMAKE_CURRENT_SOURCE_DIR}/openjpeg/src/lib/openjp2/opj_config_private.h.cmake.in
  ${CMAKE_CURRENT_BINARY_DIR}/src/lib/openjp2/opj_config_private.h
)

# Include directories
include_directories(
  ${CMAKE_CURRENT_SOURCE_DIR}/openjpeg/src/lib/openjp2
  ${CMAKE_CURRENT_BINARY_DIR}/src/lib/openjp2
)

# Source files (Explicitly excluding test/bench/generator files and JPIP managers)
set(OPENJPEG_SRCS
  openjpeg/src/lib/openjp2/thread.c
  openjpeg/src/lib/openjp2/bio.c
  openjpeg/src/lib/openjp2/cio.c
  openjpeg/src/lib/openjp2/dwt.c
  openjpeg/src/lib/openjp2/event.c
  openjpeg/src/lib/openjp2/image.c
  openjpeg/src/lib/openjp2/invert.c
  openjpeg/src/lib/openjp2/j2k.c
  openjpeg/src/lib/openjp2/jp2.c
  openjpeg/src/lib/openjp2/mct.c
  openjpeg/src/lib/openjp2/mqc.c
  openjpeg/src/lib/openjp2/openjpeg.c
  openjpeg/src/lib/openjp2/opj_clock.c
  openjpeg/src/lib/openjp2/pi.c
  openjpeg/src/lib/openjp2/t1.c
  openjpeg/src/lib/openjp2/t2.c
  openjpeg/src/lib/openjp2/tcd.c
  openjpeg/src/lib/openjp2/tgt.c
  openjpeg/src/lib/openjp2/function_list.c
  openjpeg/src/lib/openjp2/opj_malloc.c
  openjpeg/src/lib/openjp2/sparse_array.c
  openjpeg/src/lib/openjp2/ht_dec.c
)

add_executable(openjpeg_core wrapper.c ${OPENJPEG_SRCS})

# Compiler definitions
target_compile_definitions(openjpeg_core PRIVATE OPJ_STATIC)

# Emscripten specific options
# Note: We rely on the toolchain file for emcc setup.
# -flto is important for dead code elimination (stripping unused encoder functions in j2k.c)
set_target_properties(openjpeg_core PROPERTIES
    SUFFIX ".wasm"
    LINK_FLAGS "-O3 -s WASM=1 -s STANDALONE_WASM -s ALLOW_MEMORY_GROWTH=1 -s \"EXPORTED_FUNCTIONS=['_decodeToBmp', '_malloc', '_free', '_getLastError']\" --no-entry -flto"
)
