#!/bin/bash
set -e

# Clean previous coverage
rm -f *.gcda *.gcno coverage.info coverage_filtered.info coverage_summary.txt

cleanup() {
  rm -f test_wrapper_cov *.gcda *.gcno coverage.info coverage_filtered.info
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
    lcov --capture --directory . --output-file coverage.info
    lcov --extract coverage.info '*wrapper.c' --output-file coverage_filtered.info
    lcov --remove coverage_filtered.info '*test_wrapper.c' --output-file coverage_filtered.info
    lcov --list coverage_filtered.info > coverage_summary.txt
    cat coverage_summary.txt
else
    echo "lcov not found, skipping report generation."
fi

# Clean up
# rm -f test_wrapper_cov (handled by trap)
