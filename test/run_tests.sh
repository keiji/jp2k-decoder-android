#!/bin/bash
set -e

# Compile the test
gcc -o test_wrapper test/test_wrapper.c test/stubs.c \
    -I. \
    -Iopenjpeg/src/lib/openjp2 \
    -Itest \
    -DOPJ_STATIC \
    -g

# Run the test
./test_wrapper

# Clean up
rm test_wrapper
