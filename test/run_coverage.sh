#!/bin/bash
set -e

# Clean previous coverage
rm -f *.gcda *.gcno coverage.info coverage_summary.txt

cleanup() {
  rm -f test_wrapper_cov *.gcda *.gcno coverage.info coverage_filtered.info coverage_tmp.info
}
trap cleanup EXIT

# Compile with coverage flags
gcc -o test_wrapper_cov test/test_wrapper.c test/stubs.c \
    -I. -Iopenjpeg/src/lib/openjp2 -Itest -DOPJ_STATIC \
    -fprofile-arcs -ftest-coverage -g

# Run the test executable
./test_wrapper_cov

# Capture coverage (only if lcov is available)
if command -v lcov >/dev/null 2>&1; then
    # Capture all coverage
    lcov --capture --directory . --output-file coverage.info

    echo "=== Files captured by lcov (Debug) ==="
    lcov --list coverage.info
    echo "======================================"

    # Robust Filtering Strategy:
    # 1. Extract specifically 'wrapper.c' (assuming it's in the root or close to it).
    #    '*/wrapper.c' should catch /path/to/wrapper.c or ./wrapper.c
    lcov --extract coverage.info '*/wrapper.c' --output-file coverage_tmp.info --ignore-errors unused

    # 2. Explicitly remove test_wrapper.c if it got caught by the above pattern (unlikely but safe).
    #    Also remove any system headers that might have slipped in if they match the pattern (very unlikely).
    lcov --remove coverage_tmp.info '*/test_wrapper.c' --output-file coverage_filtered.info --ignore-errors unused

    echo "=== Final Coverage Report ==="
    lcov --list coverage_filtered.info > coverage_summary.txt
    cat coverage_summary.txt
else
    echo "lcov not found, skipping report generation."
fi
