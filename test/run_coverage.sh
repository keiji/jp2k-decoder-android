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
    # Using --ignore-errors mismatch to handle potential checksum issues
    lcov --capture --directory . --output-file coverage.info --ignore-errors mismatch,empty

    echo "=== Files captured by lcov (Debug) ==="
    lcov --list coverage.info
    echo "======================================"

    # Strategy:
    # 1. Extract anything matching '*wrapper.c'.
    #    This intentionally matches 'wrapper.c' and 'test/test_wrapper.c'.
    #    Using --ignore-errors unused to be safe.
    lcov --extract coverage.info '*wrapper.c' --output-file coverage_tmp.info --ignore-errors unused

    # 2. Explicitly remove 'test_wrapper.c' from the result.
    #    Using --ignore-errors unused to prevent failure if it was already excluded.
    lcov --remove coverage_tmp.info '*test_wrapper.c' --output-file coverage_filtered.info --ignore-errors unused

    echo "=== Final Coverage Report ==="
    lcov --list coverage_filtered.info > coverage_summary.txt
    cat coverage_summary.txt
else
    echo "lcov not found, skipping report generation."
fi
