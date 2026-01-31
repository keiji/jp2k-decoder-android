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

    # Strategy:
    # 1. Extract ONLY files ending in 'wrapper.c' (This includes wrapper.c and test_wrapper.c)
    #    This automatically excludes openjpeg, stubs, system headers, etc.
    lcov --extract coverage.info '*wrapper.c' --output-file coverage_tmp.info

    # 2. Remove 'test_wrapper.c' from the result to leave only the production code.
    lcov --remove coverage_tmp.info '*test_wrapper.c' --output-file coverage_filtered.info

    echo "=== Final Coverage Report ==="
    lcov --list coverage_filtered.info > coverage_summary.txt
    cat coverage_summary.txt
else
    echo "lcov not found, skipping report generation."
fi
